package rollerderby.mvp.adapter.cli

import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

private[mvp] object LocalInputPaths:
  val inputs: InputPaths = new InputPaths(path =>
    Try {
      val directory = Path.of(path).nn
      Option.when(Files.isDirectory(directory)) {
        Using.resource(Files.list(directory).nn) { entries =>
          entries.iterator().nn.asScala
            .filter(entry => Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
            .map(entry => DirectoryFile(entry.getFileName.nn.toString, entry.toString))
            .toVector
        }
      }
    }.toEither.left.map(error => s"cannot list directory: ${Option(error.getMessage).getOrElse("unknown error")}")
  )
