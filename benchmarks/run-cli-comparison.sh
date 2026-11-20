#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

cd "$(dirname "$0")/.."
results="${1:-target/crg-cli-benchmarks}"
mkdir -p "$results"
fixtures=(fixtures/samples/*.json)
if (( ${#fixtures[@]} == 0 )); then
  printf 'No CRG fixtures found\n' >&2
  exit 1
fi
smallest=$(wc -c "${fixtures[@]}" | sort -n | awk 'NR == 1 {print $2}')
time_bin=$(type -P time)
package="rollerderby.mvp.benchmarks"
jvm_sources='set cliJVM / Compile / unmanagedSources ++= Seq(file("benchmarks/src/cli/shared/CliTiming.scala"), file("benchmarks/src/cli/jvm/ReaderMains.scala"))'
js_sources='set cliJS / Compile / unmanagedSources ++= Seq(file("benchmarks/src/cli/shared/CliTiming.scala"), file("benchmarks/src/cli/js/ReaderMains.scala"))'
print_classpath='set cliJVM / Runtime / fullClasspath := { val entries = (cliJVM / Runtime / fullClasspath).value; val converter = (cliJVM / fileConverter).value; println("BENCHMARK_CLASSPATH=" + entries.map(entry => converter.toPath(entry.data).toString).mkString(java.io.File.pathSeparator)); Def.uncached(entries) }'

sbt -Dsbt.color=false "$jvm_sources; $print_classpath; cliJVM/Runtime/fullClasspath" > "$results/jvm-build.log"
classpath=$(sed -n 's/^BENCHMARK_CLASSPATH=//p' "$results/jvm-build.log" | tail -n 1)
test -n "$classpath"

for main in Ujson Jsoniter; do
  sbt -Dsbt.color=false "$js_sources; set cliJS / Compile / mainClass := Some(\"$package.${main}Main\"); cliJS/fullLinkJS; show cliJS/Compile/fullLinkJS/scalaJSLinkerOutputDirectory" > "$results/node-$main-build.log"
  output=$(sed -n 's/^\[info\] \(\/.*-opt\)$/\1/p' "$results/node-$main-build.log" | tail -n 1)
  cp "$output/main.js" "$results/$main.js"
  java -cp "$classpath" "$package.${main}Main" inspect "$smallest" > "$results/$main.jvm.out"
  node "$results/$main.js" inspect "$smallest" > "$results/$main.node.out"
done
diff -u "$results/Ujson.jvm.out" "$results/Jsoniter.jvm.out"
diff -u "$results/Ujson.jvm.out" "$results/Ujson.node.out"
diff -u "$results/Ujson.jvm.out" "$results/Jsoniter.node.out"

metrics="$results/cli.tsv"
printf 'platform\treader\tworkload\ttrial\titeration\tms\tpeak_rss_kib\n' > "$metrics"
for platform in jvm node; do
  for trial in 1 2 3 4 5 6; do
    readers=(Ujson Jsoniter)
    if (( trial % 2 == 0 )); then readers=(Jsoniter Ujson); fi
    for main in "${readers[@]}"; do
      if [[ "$platform" == jvm ]]; then
        app=(java -Xms256m -Xmx768m -cp "$classpath" "$package.${main}Main")
      else
        app=(node "$results/$main.js")
      fi

      # Include process startup and stdout formatting for one export.
      elapsed=$("$time_bin" -f '%e %M' "${app[@]}" inspect "$smallest" 2>&1 >/dev/null)
      awk -v p="$platform" -v r="$main" -v i="$trial" \
        '{printf "%s\t%s\tfresh\t%s\t1\t%.1f\t%s\n", p, r, i, $1 * 1000, $2}' <<< "$elapsed" >> "$metrics"

      # Warm up and time the full corpus in the same process, with a no-op output sink.
      "$time_bin" -f '%M' -o "$results/peak-rss.tmp" \
        "${app[@]}" --bench 4 5 "${fixtures[@]}" > "$results/batch.tmp"
      awk -v p="$platform" -v r="$main" -v t="$trial" -v rss="$(cat "$results/peak-rss.tmp")" \
        '{printf "%s\t%s\twarm-batch\t%s\t%d\t%s\t%s\n", p, r, t, NR, $1, rss}' \
        "$results/batch.tmp" >> "$metrics"
    done
  done
done

: > "$results/bundle-bytes.tsv"
for main in Ujson Jsoniter; do
  printf '%s\t%s\t%s\n' "$main" "$(wc -c < "$results/$main.js")" \
    "$(gzip -n -c "$results/$main.js" | wc -c)" >> "$results/bundle-bytes.tsv"
done
printf 'Measurements in %s\n' "$results"
