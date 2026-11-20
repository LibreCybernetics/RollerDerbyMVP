import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.9.0"

ThisBuild / semanticdbEnabled := true
ThisBuild / scalacOptions ++= Seq(
  "-Wall",
  "-Werror",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:imports",
  "-language:strictEquality",
  "-Yexplicit-nulls",
  "-Yno-flexible-types"
)

lazy val wartremoverSettings = Seq(
  wartremoverErrors ++= Seq(
    Wart.ArrayEquals,
    Wart.AsInstanceOf,
    Wart.EitherProjectionPartial,
    Wart.GetOrElseNull,
    Wart.GlobalExecutionContext,
    Wart.IsInstanceOf,
    Wart.IterableOps,
    Wart.Null,
    Wart.OptionPartial,
    Wart.PartialFunctionApply,
    Wart.PlatformDefault,
    Wart.Return,
    Wart.SeqApply,
    Wart.TripleQuestionMark,
    Wart.TryPartial
  )
)

lazy val scalaJSValidationSettings = Seq(
  scalaJSLinkerConfig ~= (_.withCheckIR(true))
)

lazy val sampleFixtureSettings = Seq(
  Test / sourceGenerators += Def.task {
    val directory = (LocalRootProject / baseDirectory).value / "fixtures" / "samples"
    require(directory.isDirectory, s"Sample fixture directory is missing: $directory")
    val filenames = (directory * "*.json").get().filter(_.isFile).map(_.getName).sorted

    def quote(value: String): String = "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char => char.toString
    } + "\""

    val managed = (Test / sourceManaged).value
    val invalidUtf8 = managed / "invalid-utf8.json"
    if (!invalidUtf8.exists) IO.write(invalidUtf8, Array(0xc3.toByte, 0x28.toByte))
    val contents = s"""package rollerderby.mvp
                       |
                       |private[mvp] object SampleFixtures:
                       |  val filenames: Vector[String] = Vector(${filenames.map(quote).mkString(", ")})
                       |  def path(filename: String): String = ${quote(directory.getAbsolutePath + "/")} + filename
                       |  val invalidUtf8Path: String = ${quote(invalidUtf8.getAbsolutePath)}
                       |""".stripMargin
    val output = managed / "rollerderby" / "mvp" / "SampleFixtures.scala"
    if (!output.exists || IO.read(output) != contents) IO.write(output, contents)
    Def.uncached(Seq(output))
  }.taskValue
)

lazy val root = project.in(file("."))
  .aggregate(coreJVM, coreJS, crgImportJVM, crgImportJS, cliJVM, cliJS)
  .settings(publish / skip := true)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(wartremoverSettings)
  .jsSettings(scalaJSValidationSettings)
  .settings(publish / skip := true)

lazy val crgImport = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("crg-import"))
  .dependsOn(core)
  .settings(wartremoverSettings)
  .settings(sampleFixtureSettings)
  .jsSettings(scalaJSValidationSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % "4.4.3",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.43.0",
      "org.scalameta" %% "munit" % "1.2.3" % Test
    ),
    publish / skip := true
  )
  .jvmSettings(
    strykerMutate := Seq("shared/src/main/scala/**/*.scala")
  )
  .jsSettings(
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val cli = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("cli"))
  .dependsOn(core, crgImport)
  .settings(wartremoverSettings)
  .settings(sampleFixtureSettings)
  .jsSettings(scalaJSValidationSettings)
  .settings(
    name := "roller-derby-mvp",
    Compile / mainClass := Some("rollerderby.mvp.Main"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.3" % Test,
    publish / skip := true
  )
  .jvmSettings(
    strykerMutate := Seq("shared/src/main/scala/**/*.scala"),
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

lazy val benchmarks = project.in(file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .dependsOn(crgImportJVM)
  .settings(wartremoverSettings)
  .settings(publish / skip := true)
