import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.8.3"

lazy val root = project.in(file("."))
  .aggregate(coreJVM, coreJS, crgImportJVM, crgImportJS, cliJVM, cliJS)
  .settings(publish / skip := true)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(publish / skip := true)

lazy val crgImport = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("crg-import"))
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "ujson" % "4.4.3",
      "org.scalameta" %%% "munit" % "1.2.3" % Test
    ),
    publish / skip := true
  )

lazy val cli = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("cli"))
  .dependsOn(core, crgImport)
  .settings(
    name := "roller-derby-mvp",
    Compile / mainClass := Some("rollerderby.mvp.Main"),
    libraryDependencies += "org.scalameta" %%% "munit" % "1.2.3" % Test,
    publish / skip := true
  )
  .jvmSettings(
    Compile / run / fork := true,
    Compile / run / baseDirectory := (LocalRootProject / baseDirectory).value
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js
lazy val crgImportJVM = crgImport.jvm
lazy val crgImportJS = crgImport.js
lazy val cliJVM = cli.jvm
lazy val cliJS = cli.js
