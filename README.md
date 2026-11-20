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

### Mutation testing and microbenchmarks

Run Stryker4s on demand from the repository root. Each command mutates the selected module's shared production code and runs its existing JVM MUnit suites.

```sh
sbt 'crgImportJVM/stryker'
sbt 'cliJVM/stryker'
```

Stryker4s prints the report location at the end of each run. Mutation scores are informational.

The JVM-only JMH project can be checked with `sbt 'benchmarks/Jmh/compile'`. When benchmarks are added under `benchmarks/src/main/scala`, use `sbt 'benchmarks/Jmh/run <pattern>'` to run selected benchmarks. Replace `<pattern>` with a benchmark name or regular expression.

## CRG exports and samples

Place sample exports in [fixtures/samples](fixtures/samples/). If known, include the CRG release and whether each file is a single-game export or a full scoreboard export.

The reader imports the flat `state` object in CRG ScoreBoard exports. It reads game records under `ScoreBoard.Game(id)` from single-game and full scoreboard exports. The reader was checked against the supplied v2027.1 game exports, and the shared test suite includes a synthetic multi-game export. Summaries show the recorded scores, roster counts, and number of jams in game periods.

## Source layout

- `core/shared` contains the domain model, import interfaces, and import service.
- `crg-import/shared` decodes CRG JSON exports. `crg-import/jvm` and `crg-import/js` provide UTF-8 file access.
- `cli/shared` handles commands and formats results. `cli/jvm` and `cli/js` provide the platform entry points.
