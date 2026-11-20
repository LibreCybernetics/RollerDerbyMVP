package rollerderby.mvp.adapter.cli

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.Try

private[mvp] object LocalInputPaths:
  val inputs: InputPaths = new InputPaths(path =>
    val isDirectory = Try(NodeDirectory.statSync(path).isDirectory()).getOrElse(false)
    Try {
      Option.when(isDirectory) {
        val prefix = if path.endsWith(NodePath.sep) || path.endsWith("/") then path else path + NodePath.sep
        val options = js.Dynamic.literal(withFileTypes = true)
        NodeDirectory.readdirSync(path, options).iterator.filter(_.isFile()).map { entry =>
          DirectoryFile(entry.name, prefix + entry.name)
        }.toVector
      }
    }.toEither.left.map(error => s"cannot list directory: ${Option(error.getMessage).getOrElse("unknown error")}")
  )

@js.native
@JSImport("node:fs",JSImport.Namespace)
private object NodeDirectory extends js.Object:
  def statSync(path: String): NodeStats = js.native
  def readdirSync(path: String, options: js.Object): js.Array[NodeDirent] = js.native

@js.native
private trait NodeStats extends js.Object:
  def isDirectory(): Boolean = js.native

@js.native
private trait NodeDirent extends js.Object:
  val name: String = js.native
  def isFile(): Boolean = js.native

@js.native
@JSImport("node:path",JSImport.Namespace)
private object NodePath extends js.Object:
  val sep: String = js.native
