package scala.cli.signing.util

import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.Security
import java.time.Instant

import scala.cli.signing.shared.Secret
import scala.publish.Content

class BouncycastleSignerTest extends munit.FunSuite {
  Security.addProvider(new BouncyCastleProvider)

  private lazy val secretKeyBytes: Array[Byte] = {
    val uri = Thread.currentThread().getContextClassLoader
      .getResource("test-keys/key.skr")
      .toURI
    os.read.bytes(os.Path(Paths.get(uri)))
  }

  private def signer(): BouncycastleSigner =
    BouncycastleSigner(
      secretKey = Secret(secretKeyBytes),
      passwordOpt = Some(Secret("1234"))
    )

  private def verifies(
    signer: BouncycastleSigner,
    signature: String,
    content: Array[Byte]
  ): Boolean = {
    val sig = BouncycastleSigner
      .readSignature(new ByteArrayInputStream(signature.getBytes(StandardCharsets.UTF_8)))
      .fold(err => fail(err), identity)
    BouncycastleSigner.verifySignature(
      sig,
      signer.pgpSecretKey.getPublicKey,
      new ByteArrayInputStream(content)
    )
  }

  test("init") {
    signer()
  }

  test("sign streams its input") {
    // Larger than the read buffer, so that it's fed to the signature in several chunks
    val bytes  = Array.tabulate[Byte](3 * BouncycastleSigner.bufferSize + 17)(i => (i % 251).toByte)
    var closed = false
    var maxChunk = 0
    val is       = new ByteArrayInputStream(bytes) {
      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        val n = super.read(b, off, len)
        maxChunk = math.max(maxChunk, n)
        n
      }
      override def close(): Unit = {
        closed = true
        super.close()
      }
    }

    val signer0   = signer()
    val signature = signer0.sign(is).fold(err => fail(err), identity)
    assert(maxChunk <= BouncycastleSigner.bufferSize)
    assert(!closed, "sign(InputStream) shouldn't close the stream it was passed")
    assert(verifies(signer0, signature, bytes))
    assert(!verifies(signer0, signature, bytes.updated(0, 42.toByte)))
  }

  test("sign files and in-memory content") {
    val bytes   = "Hello, scala-cli-signing".getBytes(StandardCharsets.UTF_8)
    val signer0 = signer()
    val tmpDir  = os.temp.dir(prefix = "scala-cli-signing-tests")
    try {
      val file = tmpDir / "hello.txt"
      os.write(file, bytes)
      val contents = Seq(Content.File(file.toNIO), Content.InMemory(Instant.EPOCH, bytes))
      for (content <- contents) {
        val signature = signer0.sign(content).fold(err => fail(err), identity)
        assert(verifies(signer0, signature, bytes), s"invalid signature for $content")
      }
    }
    finally os.remove.all(tmpDir)
  }
}
