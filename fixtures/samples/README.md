# CRG sample exports

This directory contains 13 single-game exports from the November 2026 tournament, all from CRG ScoreBoard `v2027.1`.
The JVM and Node test suites load these files through their respective file adapters.

When adding or renaming a sample, update the expected filename and game summary in
[CrgSampleSuite](../../crg-import/shared/src/test/scala/rollerderby/mvp/adapter/crg/CrgSampleSuite.scala).
Record the CRG release and whether the file is a single-game or full scoreboard export when known.
