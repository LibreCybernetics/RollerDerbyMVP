import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.8.3"

lazy val root = project.in(file("."))
  .aggregate(cliJVM, cliJS)
  .settings(publish / skip := true)

lazy val cli = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("cli"))
  .settings(
    name := "roller-derby-mvp",
    Compile / mainClass := Some("rollerderby.mvp.Main"),
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "ujson" % "4.4.3",
      "org.scalameta" %%% "munit" % "1.2.3" % Test
    ),
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

lazy val cliJVM = cli.jvm
lazy val cliJS = cli.js
