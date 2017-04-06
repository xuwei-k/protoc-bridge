package protocbridge.frontend

import java.nio.file.{Files, Path}
import protocbridge.ProtocCodeGenerator
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import java.net.ServerSocket
import java.nio.file.attribute.PosixFilePermission
import scala.collection.JavaConverters._

object PosixPluginFrontend extends PluginFrontend {

  case class InternalState(scalaScriptFile: Path)

  override def prepare(plugin: ProtocCodeGenerator): (Path, InternalState) = {
    val ss = new ServerSocket(0)
    val state = createWindowsScripts(ss.getLocalPort)

    Future {
      val client = ss.accept()
      val response = PluginFrontend.runWithInputStream(plugin, client.getInputStream)
      client.getOutputStream.write(response.toByteArray)
      client.close()
      ss.close()
    }

    (state.scalaScriptFile, state)
  }

  override def cleanup(state: InternalState): Unit = {
    Files.delete(state.scalaScriptFile)
  }

  private def createWindowsScripts(port: Int): InternalState = {
    val scalaScript = PluginFrontend.createTempFile(".scala", PluginFrontend.scalaScript)
    Files.setPosixFilePermissions(scalaScript, Set(
      PosixFilePermission.OWNER_EXECUTE,
      PosixFilePermission.OWNER_READ).asJava)
    InternalState(scalaScript)
  }
}
