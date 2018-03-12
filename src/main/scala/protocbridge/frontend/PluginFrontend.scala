package protocbridge.frontend

import java.io.{ByteArrayOutputStream, InputStream, PrintWriter, StringWriter}
import java.nio.file.{Files, Path}

import protocbridge.ProtocCodeGenerator

import scala.util.Try


/** A PluginFrontend instance provides a platform-dependent way for protoc to communicate with
  * a JVM based ProtocCodeGenerator.
  *
  * protoc is able to launch plugins. Plugins are executables that take a serialized
  * CodeGenerationRequest via stdin and serialize a CodeGenerationRequest to stdout.
  * The idea in PluginFrontend is to create a minimal plugin that wires its stdin/stdout
  * to this JVM.
  *
  * The two-way communication always goes as follows:
  *
  * 1. protoc writes a request to the stdin of a plugin
  * 2. plugin writes the data to the channel
  * 3. this JVM reads it, interprets it as CodeGenerationRequest and process it.
  * 4. this JVM writes a CodeGenerationResponse to the channel
  * 5. this JVM closes the channel.
  * 6. the plugin reads the data and writes it to standard out.
  * 7. protoc handles the CodeGenerationResponse (creates Scala sources)
  */
trait PluginFrontend {
  type InternalState

  // Notifies the frontend to set up a protoc plugin that runs the given generator. It returns
  // the system path of the executable and an arbitary internal state object that is passed
  // later. Useful for cleanup.
  def prepare(plugin: ProtocCodeGenerator): (Path, InternalState)

  def cleanup(state: InternalState): Unit
}

object PluginFrontend {
  private def getStackTrace(e: Throwable): String = {
    val stringWriter = new StringWriter
    val printWriter = new PrintWriter(stringWriter)
    e.printStackTrace(printWriter)
    stringWriter.toString
  }

  def runWithBytes(gen: ProtocCodeGenerator, request: Array[Byte]): Array[Byte] = {
    Try {
      gen.run(request)
    }.recover {
      case throwable =>
        createCodeGeneratorResponseWithError(throwable.toString + "\n" + getStackTrace(throwable))
    }.get
  }

  def createCodeGeneratorResponseWithError(error: String): Array[Byte] = {
    val b = Array.newBuilder[Byte]

    def addRawVarint32(value0: Int): Unit = {
      var value = value0
      while (true) {
        if ((value & ~0x7F) == 0) {
          b += value.toByte
          return
        } else {
          b += ((value & 0x7F) | 0x80).toByte
          value >>>= 7
        }
      }
    }

    b += 10
    val errorBytes = error.getBytes(java.nio.charset.Charset.forName("UTF-8"))
    var length = errorBytes.length
    addRawVarint32(length)
    b ++= errorBytes
    b.result()
  }

  def readInputStreamToByteArray(fsin: InputStream): Array[Byte] = {
    val b = new ByteArrayOutputStream()
    val buffer = new Array[Byte](4096)
    var count = 0
    while (count != -1) {
      count = fsin.read(buffer)
      if (count > 0) {
        b.write(buffer, 0, count)
      }
    }
    b.toByteArray
  }

  def runWithInputStream(gen: ProtocCodeGenerator, fsin: InputStream): Array[Byte] = {
    val bytes = readInputStreamToByteArray(fsin)
    runWithBytes(gen, bytes)
  }

  def createTempFile(extension: String, content: String): Path = {
    val fileName = Files.createTempFile("protocbridge", extension)
    fileName.toFile.setExecutable(true)
    val os = Files.newOutputStream(fileName)
    os.write(content.getBytes("UTF-8"))
    os.close()
    fileName
  }

  def isWindows: Boolean = sys.props("os.name").startsWith("Windows")

  def newInstance: PluginFrontend = {
    WindowsPluginFrontend
  }

  /**
    * @param pythonExe Not used now, it's here to keep binary compatibility.
    */
  @deprecated("Use 'newInstance' instead", "0.7.2")
  def newInstance(pythonExe: String = "python.exe"): PluginFrontend = newInstance
}
