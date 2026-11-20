# Roller Derby MVP

Roller Derby MVP is a Scala command-line project for analyzing tournament data exported by [CRG ScoreBoard](https://github.com/rollerderby/scoreboard). Use `inspect` to summarize one or more exports and `skater-stats` to report penalties and active jam time by team and skater. MVP categories, scoring and tie-breaks are future work.

## Development

Enter the development shell with `nix develop`. The flake supplies JDK 25, the sbt launcher, and Node.js 24. `project/build.properties` pins sbt, and `build.sbt` pins Scala and the cross-platform libraries.

```sh
sbt testFull
sbt 'cliJVM/run inspect path/to/game.json'
sbt 'cliJS/fastLinkJS; show cliJS/Compile/fastLinkJS/scalaJSLinkerOutputDirectory'
```

Use `sbt test` for incremental testing. To run the JavaScript CLI, use `node <output-directory>/main.js inspect path/to/game.json` with the linker output directory printed by sbt.

Pass multiple paths to inspect multiple exports. The command reports file and field details for import errors and returns a nonzero exit status if an import fails.

## Skater statistics

```sh
sbt 'cliJVM/run skater-stats path/to/game.json'
sbt 'cliJVM/run skater-stats --by jam path/to/game.json'
sbt 'cliJVM/run skater-stats --by tournament fixtures/samples/'
```

Game totals are the default. The Node CLI takes the same arguments. Jam rows identify the period and jam number and show skaters who were fielded or received a penalty. Game and tournament totals also include rostered skaters with zero activity. Warnings about invalid rosters or unresolved references go to stderr.

Active jam time is the sum of the recorded durations of jams in which the skater was fielded. It includes penalty-box time during those jams. A skater appearing in multiple positions in one jam gets that jam's duration once overall. The report preserves milliseconds when present, for example `23m50.250s`. A duration marked `(partial)` indicates missing jam times or uncertain role allocations.

Penalties are counted from numbered skater penalty records starting at 1 and assigned using the recorded jam link. Game and tournament totals include penalties without a usable jam link.

Each team has its own section. Each skater's number and name appear once per breakdown, alongside the **All roles** row, followed by **Pack** (blockers and pivots) and **Jammer**. A star pass moves the pivot to Jammer and the original jammer to Pack. A skater who plays both roles in a jam gets one appearance in each role and one appearance overall.

An **Unattributed** row appears when active jam time, penalties, or points cannot be fully assigned by role. The Pack, Jammer, and Unattributed rows add up to All roles for recorded time, penalties, and points. The Unattributed row shows dashes for jams and per-minute rates. Its penalty count includes penalties without a usable jam link, displayed as, for example, `3 (2 without jam)`.

`Points for` and `Points against` are the team's and opponent's scores during the skater's participation. The overall row uses the recorded jam scores. When a skater switches roles, the report uses the team's recorded after-pass score and any scoring trips that can be placed within a single role interval. Uncertain allocations and missing scores make the relevant totals partial. Penalty and scoring rates divide the totals by active jam minutes, using recorded precision. `—` indicates an unavailable rate when the duration is zero or the relevant count or time is incomplete.

CRG records star passes separately for each team. The linked scoring trip identifies the pass in the scoresheet. The report's timing summary counts each team pass separately and shows the amount of skater time still unattributed. Penalty entry time can lag behind the occurrence and leave the skater's role uncertain.

Use `--box-trip-estimates` to include partial role time from penalty-box records. The latest same-team box event marked before the pass and the earliest marked after it bracket the pass. The report credits time outside that window to the corresponding roles, labels those durations as box estimates, and leaves the window unattributed. It requires a resolved fielding in the same jam, an in-jam clock value, and explicit phase flags. Penalty and point attribution continue to use their existing evidence.

Tournament totals cover all games in the supplied files. Identical game records count once. An import failure or conflicting records for a game prevent tournament totals from being printed. Prepared team and skater IDs link appearances across games. Game-local IDs are displayed with their source game and side and are flagged in a warning.

### Penalty report

```sh
sbt 'cliJVM/run penalty-stats fixtures/samples/'
```

`penalty-stats` prints a grand total, each game's subtotal with its team and skater counts, and each team and skater's count across all supplied games. It includes rostered skaters with zero penalties and uses the same counting and deduplication rules as `skater-stats`. The unassigned count is included in the penalty total.

Both statistics commands accept any mix of files and directories. A directory contributes its immediate regular files with a `.json` extension, regardless of case, in filename order. With `fixtures/samples/`, the report reads all 14 exports. A directory with no matching files produces an error. Jam and game reports still show successfully loaded inputs when another input fails. Tournament and penalty reports require every input to load successfully.

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

The domain uses `Instant` for wall-clock timestamps, `Duration` for recorded durations, and `ClockReading` for positions on a scoreboard clock. Zero wall-clock endpoints indicate missing timestamps. Clock readings and durations retain explicit zeros and signed values. Event date and start time are local calendar values, and each period can also carry the scoreboard host's recorded zoned start time.

The `inspect` command summarizes scores, selected game-roster counts, and historical jams. Roster issues are reported alongside the game summary. Use `ImportGamesService.importTournament` to assemble an explicitly grouped set of files. Prepared IDs link teams and skaters across those games. A game's listed skaters remain available as charter evidence, including alternates. Supply charter rosters to validate that the selected skaters belong to each team's charter.

## Source layout

- `core/shared` contains the domain model, import interfaces, and import service.
- `crg-import/shared` decodes CRG JSON exports. `crg-import/jvm` and `crg-import/js` provide UTF-8 file access.
- `cli/shared` handles commands and formats results. `cli/jvm` and `cli/js` provide the platform entry points.
