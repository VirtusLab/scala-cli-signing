package scala.cli.signing

import java.io.IOException
import java.net.URI
import java.security.cert.{CertPathBuilderException, CertPathValidatorException}
import javax.net.ssl.HttpsURLConnection

import scala.concurrent.duration.*

/** Verifies that the JDK the tests run on (i.e. the one set up on the CI) doesn't trust the
  * compromised eMudhra intermediate CAs (CVE-2026-91403).
  *
  * The native launchers don't do any TLS, and their trust store is stripped of those CAs at build
  * time (see `CliNativeImage.trustStoreDir` in `build.mill`), so only the JDK is checked here.
  *
  * See https://www.scala-lang.org/blog/jdk-certificate-vulnerability.html
  */
class CompromisedCaTests extends munit.FunSuite {
  override def munitTimeout: Duration = 5.minutes

  // a host with a legitimate certificate chain, which the JDK must trust
  private val controlUrl = "https://repo1.maven.org/maven2/"

  private val testUrls =
    for {
      validation <- List("dv", "ov", "ev")
      aaa        <- List("", "-aaa")
      generation <- List("g3b", "g3c")
    } yield s"https://$validation-$generation$aaa-emudhra.we-have-a-problem.com/"

  private enum TlsResult {
    // the TLS handshake succeeded, so the certificate chain was accepted
    case Trusted(status: Int)
    // either no trust anchor for the chain ("PKIX path building failed", e.g. the CAs got removed
    // from the trust store), or a certificate of the chain is explicitly distrusted by the JDK
    // ("Untrusted certificate", what the patched JDKs do)
    case Untrusted
    case Error(message: String)
  }

  test("JDK does not trust the compromised CAs") {
    val javaInfo = s"(JDK: ${sys.props("java.home")}, ${sys.props("java.vendor")} " +
      s"${sys.props("java.runtime.version")})"
    // if TLS doesn't work at all (e.g. no network, an intercepting proxy, a broken trust store),
    // the compromised hosts would get rejected too, which proves nothing
    checkTls(controlUrl) match {
      case TlsResult.Trusted(_) =>
      case other => fail(s"TLS handshake with the control host failed $javaInfo: $other")
    }
    val failures = testUrls.flatMap { url =>
      checkTls(url) match {
        case TlsResult.Untrusted       => None
        case TlsResult.Trusted(status) => Some(s"VULNERABLE (HTTP $status) $url")
        // DNS, timeouts, expired certificates, protocol errors etc. say nothing about the CAs
        case TlsResult.Error(e) => Some(s"ERROR ($e) $url")
      }
    }
    assert(
      failures.isEmpty,
      s"""Compromised CAs are trusted by the JDK, or an unexpected error was encountered $javaInfo:
         |${failures.mkString(System.lineSeparator())}""".stripMargin
    )
  }

  private def checkTls(url: String): TlsResult = {
    val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpsURLConnection]
    try {
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(15000)
      TlsResult.Trusted(conn.getResponseCode)
    }
    catch {
      case e: IOException =>
        // the certificate path exception is wrapped, so walk the causes
        val causes = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null)
        if causes.exists(t => t.isInstanceOf[CertPathBuilderException] || isDistrusted(t)) then
          TlsResult.Untrusted
        else TlsResult.Error(e.toString)
    }
    finally conn.disconnect()
  }

  // other validation failures (expired certificates etc.) say nothing about the CAs
  private def isDistrusted(t: Throwable): Boolean =
    t match {
      case e: CertPathValidatorException =>
        Option(e.getMessage).exists(_.startsWith("Untrusted certificate"))
      case _ => false
    }
}
