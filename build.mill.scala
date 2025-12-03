//| mvnDeps:
//| - io.github.alexarchambault.mill::mill-native-image::0.2.4
//| - io.github.alexarchambault.mill::mill-native-image-upload:0.2.4
//| - com.goyeau::mill-scalafix::0.6.0
//| - com.lumidion::sonatype-central-client-requests:0.6.0
package build

import build.project.publish.{finalPublishVersion, publishSonatype => publishSonatype0}
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill.*
import mill.api.{BuildCtx, Task}
import mill.scalalib.*
import mill.util.{Tasks, VcsVersion}

import java.io.File
import scala.annotation.unused
import com.goyeau.mill.scalafix.ScalafixModule

object Deps {
  object Versions {
    def jsoniterScala = "2.38.5"
    def bouncycastle  = "1.83"
  }
  def bouncycastle      = mvn"org.bouncycastle:bcpg-jdk18on:${Versions.bouncycastle}"
  def bouncycastleUtils = mvn"org.bouncycastle:bcutil-jdk18on:${Versions.bouncycastle}"
  def caseApp           = mvn"com.github.alexarchambault::case-app:2.1.0"
  def coursierPublish   = mvn"io.get-coursier.publish::publish:0.4.3"
  def expecty           = mvn"com.eed3si9n.expecty::expecty:0.17.1"
  def jsoniterCore      =
    mvn"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterMacros =
    mvn"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def munit = mvn"org.scalameta::munit:1.2.1"
  def osLib = mvn"com.lihaoyi::os-lib:0.11.6"
  def svm   = mvn"org.graalvm.nativeimage:svm:$graalVmVersion"

  def graalVmVersion      = "22.3.1"
  def graalVmId           = s"graalvm-java17:$graalVmVersion"
  def coursierVersion     = "2.1.25-M19"
  def ubuntuDockerVersion = "ubuntu:24.04"
}

object Scala {
  def scala3 = "3.3.7"
}

def ghOrg      = "VirtusLab"
def ghName     = "scala-cli-signing"
def publishOrg = "org.virtuslab.scala-cli-signing"
trait ScalaCliSigningPublish extends SonatypeCentralPublishModule {
  import mill.scalalib.publish.*
  override def pomSettings: T[PomSettings] = PomSettings(
    description = artifactName(),
    organization = publishOrg,
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github(ghOrg, ghName),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    )
  )
  override def publishVersion: T[String] = finalPublishVersion()
}

trait ScalaCliSigningModule extends ScalaModule with ScalafixModule {
  override def scalacOptions: T[Seq[String]] =
    super.scalacOptions() ++ Seq("-Wunused:all")
  override def scalaVersion: T[String] = Scala.scala3
}

object shared extends Shared
trait Shared  extends ScalaCliSigningModule with ScalaCliSigningPublish {
  override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
    Deps.jsoniterCore,
    Deps.osLib
  )
  override def compileMvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
    Deps.jsoniterMacros
  )
}

trait CliNativeImage extends NativeImage {
  override def generateNativeImageWithFileSystemChecker: Boolean = false
  override def nativeImagePersist: Boolean                       = System.getenv("CI") != null
  override def nativeImageGraalVmJvmId: T[String]                = Deps.graalVmId
  override def nativeImageName                                   = "scala-cli-signing"
  override def nativeImageClassPath: T[Seq[PathRef]]             = `native-cli`.runClasspath()
  override def nativeImageMainClass: T[String]                   = Task {
    `native-cli`.mainClass().getOrElse(sys.error("no main class found"))
  }
  override def nativeImageOptions: T[Seq[String]] = super.nativeImageOptions() ++ Seq(
    "--no-fallback",
    "--rerun-class-initialization-at-runtime=org.bouncycastle.jcajce.provider.drbg.DRBG$Default,org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV"
  )

  def nameSuffix = ""

  @unused
  def copyToArtifacts(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val _ = Upload.copyLauncher0(
      nativeLauncher = nativeImage().path,
      directory = directory,
      name = "scala-cli-signing",
      compress = true,
      suffix = nameSuffix,
      workspace = BuildCtx.workspaceRoot
    )
  }
}

object cli extends Cli
trait Cli  extends ScalaCliSigningModule with ScalaCliSigningPublish { self =>
  override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
    Deps.bouncycastle,
    Deps.bouncycastleUtils,
    Deps.caseApp,
    Deps.coursierPublish // we can probably get rid of that one
  )
  override def moduleDeps: Seq[Shared]      = Seq(shared)
  override def mainClass: T[Option[String]] = Some("scala.cli.signing.ScalaCliSigning")

  object test extends ScalaTests with TestModule.Munit {
    override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
      Deps.expecty,
      Deps.munit,
      Deps.jsoniterMacros
    )
    override def forkArgs: T[Seq[String]] = Task {
      super.forkArgs() ++ Seq("-Xmx512m", "-Xms128m", "--add-opens=java.base/java.util=ALL-UNNAMED")
    }
  }
}
object `native-cli` extends ScalaCliSigningModule with ScalaCliSigningPublish { self =>
  override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(Deps.svm)
  override def moduleDeps: Seq[Cli] = Seq(cli)

  override def mainClass: T[Option[String]] = cli.mainClass()

  object `base-image`   extends CliNativeImage
  object `static-image` extends CliNativeImage {
    private def helperImageName = "scala-cli-signing-musl"
    override def nativeImageDockerParams: T[Option[NativeImage.DockerParams]] = Task {
      buildHelperImage()
      Some(
        NativeImage.linuxStaticParams(
          s"$helperImageName:latest",
          s"https://github.com/coursier/coursier/releases/download/v${Deps.coursierVersion}/cs-x86_64-pc-linux.gz"
        )
      )
    }
    def buildHelperImage: T[Unit] = Task {
      os.proc("docker", "build", "-t", helperImageName, ".")
        .call(cwd = BuildCtx.workspaceRoot / "project" / "musl-image", stdout = os.Inherit)
      ()
    }
    override def writeNativeImageScript(scriptDest: String, imageDest: String = ""): Command[Unit] =
      Task.Command {
        buildHelperImage()
        super.writeNativeImageScript(scriptDest, imageDest)()
      }
    override def nameSuffix = "-static"
  }

  object `mostly-static-image` extends CliNativeImage {
    override def nativeImageDockerParams: T[Option[NativeImage.DockerParams]] = Some(
      NativeImage.linuxMostlyStaticParams(
        Deps.ubuntuDockerVersion,
        s"https://github.com/coursier/coursier/releases/download/v${Deps.coursierVersion}/cs-x86_64-pc-linux.gz"
      )
    )
    override def nameSuffix = "-mostly-static"
  }
}

def tmpDirBase: T[PathRef] = Task(persistent = true) {
  PathRef(Task.dest / "working-dir")
}

trait CliTests extends ScalaModule {
  def testLauncher: T[PathRef]
  def cliKind: T[String]

  override def scalaVersion: T[String] = Scala.scala3

  def prefix                                                 = "integration-"
  private def updateRef(name: String, ref: PathRef): PathRef = {
    val rawPath = ref.path.toString.replace(
      File.separator + name + File.separator,
      File.separator
    )
    PathRef(os.Path(rawPath))
  }
  private def mainArtifactName: T[String] = Task(artifactName())
  def modulesPath: T[PathRef]             = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      val name                = mainArtifactName().stripPrefix(prefix)
      val baseIntegrationPath = os.Path(moduleDir.toString.stripSuffix(name))
      val p                   =
        os.Path(baseIntegrationPath.toString.stripSuffix(baseIntegrationPath.baseName))
      PathRef(p)
    }
  }
  override def sources: T[Seq[PathRef]] = Task {
    val mainPath = PathRef(modulesPath().path / "integration" / "src" / "main" / "scala")
    super.sources() ++ Seq(mainPath)
  }
  override def resources: T[Seq[PathRef]] = Task {
    val mainPath = PathRef(modulesPath().path / "integration" / "src" / "main" / "resources")
    super.resources() ++ Seq(mainPath)
  }

  trait Tests extends ScalaTests with TestModule.Munit {
    override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
      Deps.expecty,
      Deps.munit,
      Deps.osLib
    )
    override def testFramework                   = "munit.Framework"
    override def forkArgs: T[Seq[String]]        = super.forkArgs() ++ Seq("-Xmx512m", "-Xms128m")
    override def forkEnv: T[Map[String, String]] = super.forkEnv() ++ Seq(
      "SIGNING_CLI"      -> testLauncher().path.toString,
      "SIGNING_CLI_KIND" -> cliKind(),
      "SIGNING_CLI_TMP"  -> tmpDirBase().path.toString
    )

    override def sources: T[Seq[PathRef]] = Task {
      val name = mainArtifactName().stripPrefix(prefix)
      super.sources().flatMap { ref =>
        Seq(updateRef(name, ref), ref)
      }
    }
    override def resources: T[Seq[PathRef]] = Task {
      val name = mainArtifactName().stripPrefix(prefix)
      super.resources().flatMap { ref =>
        Seq(updateRef(name, ref), ref)
      }
    }
  }
}

object `jvm-integration` extends JvmIntegration with ScalafixModule
trait JvmIntegration     extends ScalaModule with CliTests { self =>
  override def scalaVersion: T[String] = Scala.scala3

  override def modulesPath: T[PathRef] = super.modulesPath
  def testLauncher: T[PathRef]         = cli.launcher()
  def cliKind                          = "jvm"

  object test extends Tests
}

object `native-integration` extends Module {
  object native extends CliTests {
    def testLauncher: T[PathRef] = `native-cli`.`base-image`.nativeImage()
    def cliKind                  = "native"

    object test extends Tests
  }
  object static extends CliTests {
    def testLauncher: T[PathRef] = `native-cli`.`static-image`.nativeImage()
    def cliKind                  = "native-static"

    object test extends Tests
  }
  object `mostly-static` extends CliTests {
    def testLauncher: T[PathRef] = `native-cli`.`mostly-static-image`.nativeImage()
    def cliKind                  = "native-mostly-static"

    object test extends Tests
  }
}

object ci extends Module {
  @unused
  def upload(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val version = finalPublishVersion()

    val path      = os.Path(directory, BuildCtx.workspaceRoot)
    val launchers = os.list(path).filter(os.isFile(_)).map(path => path -> path.last)
    val ghToken   = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if (version.endsWith("-SNAPSHOT")) ("launchers", true)
      else ("v" + version, false)

    Upload.upload(
      ghOrg = ghOrg,
      ghProj = ghName,
      ghToken = ghToken,
      tag = tag,
      dryRun = false,
      overwrite = overwriteAssets
    )(launchers *)
  }

  @unused
  def publishSonatype(tasks: Tasks[PublishModule.PublishData]): Command[Unit] =
    Task.Command {
      val publishVersion = finalPublishVersion()
      System.err.println(s"Publish version: $publishVersion")
      val bundleName = s"$publishOrg-$ghName-$publishVersion"
      System.err.println(s"Publishing bundle: $bundleName")
      publishSonatype0(
        data = Task.sequence(tasks.value)(),
        log = Task.ctx().log,
        workspace = BuildCtx.workspaceRoot,
        env = Task.env,
        bundleName = bundleName
      )
    }

  @unused
  def copyJvm(jvm: String = Deps.graalVmId, dest: String = "jvm"): Command[os.Path] = Task.Command {
    import sys.process.*
    val command = os.proc(
      "cs",
      "java-home",
      "--jvm",
      jvm,
      "--update",
      "--ttl",
      "0"
    )
    val baseJavaHome = os.Path(command.call().out.text().trim, BuildCtx.workspaceRoot)
    System.err.println(s"Initial Java home $baseJavaHome")
    val destJavaHome = os.Path(dest, BuildCtx.workspaceRoot)
    os.copy(baseJavaHome, destJavaHome, createFolders = true)
    System.err.println(s"New Java home $destJavaHome")
    destJavaHome
  }
}
