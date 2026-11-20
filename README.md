# Roller Derby MVP

Roller Derby MVP is a Scala command-line project for analyzing tournament data exported by [CRG ScoreBoard](https://github.com/rollerderby/scoreboard). The initial command, `inspect`, imports one or more JSON files and summarizes the games they contain. MVP categories, scoring and tie-breaks are future work.

## Development

Enter the development shell with `nix develop`. The flake supplies JDK 25, the sbt launcher, and Node.js 24. `project/build.properties` pins sbt, and `build.sbt` pins Scala and the cross-platform libraries.

```sh
sbt testFull
sbt 'cliJVM/run inspect path/to/game.json'
sbt 'cliJS/fastLinkJS; show cliJS/Compile/fastLinkJS/scalaJSLinkerOutputDirectory'
```

Use `sbt test` for incremental testing. To run the JavaScript CLI, use `node <output-directory>/main.js inspect path/to/game.json` with the linker output directory printed by sbt.

Pass multiple paths to inspect multiple exports. The command reports file and field details for import errors and returns a nonzero exit status if an import fails.

### Compiler and lint checks

Application, test, and benchmark code compile with Scala 3 warnings treated as errors, strict equality, explicit nulls for Scala and Java interop, and WartRemover checks for unsafe operations. The Scala.js linker validates its intermediate representation.

```sh
sbt 'scalafixAll --check; benchmarks/scalafixAll --check'
```

Scalafix checks explicit result types on public and protected members, import organization, and redundant syntax. To apply a fix, run the task for one project at a time, for example `sbt 'crgImportJVM/scalafixAll'`. JVM and Scala.js projects share source files.

### Mutation testing and microbenchmarks

Run Stryker4s on demand from the repository root. Each command mutates the selected module's shared production code and runs its existing JVM MUnit suites.

```sh
sbt 'crgImportJVM/stryker'
sbt 'cliJVM/stryker'
```

Stryker4s prints the report location at the end of each run. Mutation scores are informational.

The JVM-only JMH project can be checked with `sbt 'benchmarks/Jmh/compile'`. Use `sbt 'benchmarks/Jmh/run <pattern>'` to run selected benchmarks under `benchmarks/src/main/scala`. Replace `<pattern>` with a benchmark name or regular expression.

### CRG JSON reader comparison

The CLI uses uJSON. `CrgFileGameSource.jsoniter()` selects the jsoniter-scala reader on JVM and Node. Both readers use the same UTF-8 file adapter, raw export model, and domain mapper.

```sh
nix develop --command sbt testFull
nix develop --command sbt 'benchmarks/Jmh/run -foe true -prof gc -rf json -rff target/crg-jmh.json .*CrgDecodeBenchmark.*'
nix develop --command bash benchmarks/run-cli-comparison.sh
```

The CLI comparison uses GNU `time` for elapsed time and peak RSS. It writes raw samples, optimized Node bundles, and bundle sizes to `target/crg-cli-benchmarks/`. Both benchmark entry points use the same `inspect` command. JMH checks raw export and domain equality during setup, preloads the input strings, and times parsing the complete export and mapping it to domain games.

## CRG exports and samples

Place sample exports in [fixtures/samples](fixtures/samples/). If known, include the CRG release and whether each file is a single-game export or a full scoreboard export.

Use `CrgFileGameSource().loadRaw(path)` to inspect the complete JSON export as a `CrgExport`. Object members retain their order and duplicate keys, and numbers retain their original tokens. `CrgDomainMapper.toGames(export)` maps game records under `state.ScoreBoard.Game(id)` from single-game and full scoreboard exports. `CrgFileGameSource().load(path)` runs both stages.

The domain uses `Instant` for wall-clock timestamps, `Duration` for recorded durations, and `ClockReading` for positions on a scoreboard clock. Zero wall-clock endpoints indicate missing timestamps. Clock readings and durations retain explicit zeros and signed values. Event date and start time are local calendar values, and each period can also carry the scoreboard host's recorded zoned start time. The `inspect` command summarizes scores, roster counts, and the number of historical jams.

## Source layout

- `core/shared` contains the domain model, import interfaces, and import service.
- `crg-import/shared` decodes CRG JSON exports. `crg-import/jvm` and `crg-import/js` provide UTF-8 file access.
- `cli/shared` handles commands and formats results. `cli/jvm` and `cli/js` provide the platform entry points.
