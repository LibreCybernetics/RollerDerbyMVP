# Roller Derby MVP

Roller Derby MVP is a Scala command-line project for analyzing tournament data exported by [CRG ScoreBoard](https://github.com/rollerderby/scoreboard). The initial command, `inspect`, imports one or more JSON files and summarizes the games they contain. MVP categories, scoring and tie-breaks are future work.

## Development

Enter the development shell with `nix develop`. The flake pins the JDK, sbt and Node.js toolchain. The sbt build pins Scala and the cross-platform libraries.

```sh
sbt test
sbt 'cliJVM/run inspect path/to/game.json'
sbt cliJS/fastLinkJS
node cli/js/target/scala-3.8.3/roller-derby-mvp-fastopt/main.js inspect path/to/game.json
```

Pass multiple paths to inspect multiple exports. The command reports file and field details for import errors and returns a nonzero exit status if an import fails.

## CRG exports and samples

Place sample exports in [fixtures/samples](fixtures/samples/). If known, include the CRG release and whether each file is a single-game export or a full scoreboard export.

The initial reader targets the flat `state` object written by CRG ScoreBoard 2025.8. It imports game records under `ScoreBoard.Game(id)` from either export type. The checked-in synthetic fixture exercises this format.

## Source layout

- `cli/shared` contains the domain model, import use case and ports, CLI behavior, and CRG decoder.
- `cli/jvm` and `cli/js` provide platform entry points and UTF-8 file access.
