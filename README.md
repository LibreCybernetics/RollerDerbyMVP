# Roller Derby MVP

Roller Derby MVP is a Scala command-line project for analyzing tournament data exported by [CRG ScoreBoard](https://github.com/rollerderby/scoreboard). Use `inspect` to summarize one or more exports, `skater-stats` for skater participation and penalties, and `team-stats` for match scores and team penalty breakdowns. MVP categories, scoring and tie-breaks are future work.

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

The game view includes **Versus Team Average (VTAR)** on each skater's All roles row. Penalty VTAR, Points For VTAR, and Points Against VTAR subtract the team's full-match per-minute rate from the skater's per-minute rate. The penalty baseline uses the whole team's penalty count divided by match jam time. Scoring VTAR compares team scoring during the skater's active jams with the team's recorded full-match scoring. Positive values mean a higher event rate for the skater. The report rounds after subtraction and shows `—` when either rate is unavailable.

Active jam time is the sum of the recorded durations of jams in which the skater was fielded. It includes penalty-box time during those jams. A skater appearing in multiple positions in one jam gets that jam's duration once overall. The report preserves milliseconds when present, for example `23m50.250s`.

Penalties are counted from numbered skater penalty records starting at 1 and assigned using the recorded jam link. Game and tournament totals include penalties without a usable jam link. These penalties can widen both role ranges, and the report summary gives their count.

Each team has its own section. Each skater's number and name appear once per breakdown, alongside the **All roles** row, followed by **Pack** (blockers and pivots) and **Jammer**. A star pass moves the pivot to Jammer and the original jammer to Pack. A skater who plays both roles in a jam gets one appearance in each role and one appearance overall.

#### Why role totals are ranges

Role cells show a single value when the export supports an exact attribution. A range such as `16 ± 1` means the role could have 15 to 17 penalties. The midpoint summarizes the bounds. Each ambiguous penalty can go to either role, so the two role maxima can describe different allocations. The All roles count includes each penalty once.

CRG records star passes separately for each team. A linked scoring trip can identify the pass in the scoresheet without providing the exact handoff time. With no usable timestamp or timing bounds, a confirmed pass can fall anywhere within the recorded jam duration. For example, either role could have anywhere from zero to two minutes in a two-minute jam, displayed as `1m00s ± 1m00s` for each role. Giving one role more of the jam gives the other less. Penalty entry time can lag behind the infraction, so the role at the time of the infraction may also be uncertain.

Use `--box-trip-estimates` to narrow the role time ranges. The latest eligible same-team box event marked before the pass and the earliest marked after it bound the pass time. Each event requires a resolved fielding in the same jam, an in-jam clock value, and explicit phase flags. The report labels the resulting time ranges `(box estimate)`. The timing summary counts team passes and adds the time that could go to either role once per affected skater.

`Points for` and `Points against` are the team's and opponent's scores during the skater's participation. The overall row uses the recorded jam scores. A recorded after-pass score can give an exact split for the skater's team. Scoring trips that cross the handoff or have insufficient timing information can contribute to either role. When only the jam score is available, the range lets either role receive the recorded amount. Missing jam durations or scores and trip scores that cannot be reconciled with the jam score are marked `(partial)`.

Per-minute rates use the bounds on both the count and the active time. The report rounds uncertain rates outward and shows `—` when the time could be zero or missing data prevents a finite rate range.

Tournament totals cover all games in the supplied files. Identical game records count once. An import failure or conflicting records for a game prevent tournament totals from being printed. Prepared team and skater IDs link appearances across games. Game-local IDs are displayed with their source game and side and are flagged in a warning.

### Penalty report

```sh
sbt 'cliJVM/run penalty-stats fixtures/samples/'
```

`penalty-stats` prints a grand total, each game's subtotal with its team and skater counts, and each team and skater's count across all supplied games. It includes rostered skaters with zero penalties and uses the same counting and deduplication rules as `skater-stats`. The unassigned count is included in the penalty total.

### Team statistics

```sh
sbt 'cliJVM/run team-stats fixtures/samples/'
```

`team-stats` groups matches by team and shows each game from both teams' perspectives. The match row shows the recorded game scores, point differential, number of jams, recorded jam time, and penalties. Pack and Jammer rows break down penalties by the skater's role when the infraction occurred. Uncertain role assignments appear as ranges. Game IDs distinguish repeat matchups.

Points for, points against, and penalties per minute all use the total recorded match jam time. Pack and Jammer penalty rates use that same denominator and show each role's contribution to the team rate. Role rows leave the jam time and scoring columns blank. Rates show `—` when jam time is zero or incomplete.

All statistics commands accept any mix of files and directories. A directory contributes its immediate regular files with a `.json` extension, regardless of case, in filename order. With `fixtures/samples/`, the report reads all 14 exports. A directory with no matching files produces an error. The skater jam and game views still show successfully loaded inputs when another input fails. The skater tournament view, penalty report, and team report require every input to load successfully. They count identical games once and reject conflicting records for the same game.

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
