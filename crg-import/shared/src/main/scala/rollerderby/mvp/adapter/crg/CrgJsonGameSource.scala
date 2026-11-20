package rollerderby.mvp.adapter.crg

import rollerderby.mvp.application.GameSource
import rollerderby.mvp.domain.Game

final class CrgJsonGameSource(
    readUtf8: String => Either[String, String],
    parse: CrgJsonGameSource.Parser = CrgJson.parse
) extends GameSource:
  private[mvp] def readText(path: String): Either[String, String] = readUtf8(path)

  def loadRaw(path: String): Either[String, CrgExport] = readText(path).flatMap(parse)

  def load(path: String): Either[String, Vector[Game]] =
    loadRaw(path).flatMap(CrgDomainMapper.toGames)

object CrgJsonGameSource:
  type Parser = String => Either[String, CrgExport]
