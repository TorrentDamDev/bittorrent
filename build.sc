import $ivy.`com.lihaoyi::mill-contrib-bsp:$MILL_VERSION`
import $ivy.`com.lihaoyi::mill-contrib-bintray:$MILL_VERSION`

import mill._
import scalalib._
import scalafmt.ScalafmtModule
import mill.eval.Result
import $file.release
import coursier.maven.MavenRepository
import release.ReleaseModule
import mill.contrib.bintray.{BintrayPublishData, BintrayPublishModule}

object common extends Module with Publishing {
  def ivyDeps = Agg(
    Deps.`scodec-bits`,
  )
  object js extends JsModule with Publishing {
    def sources = common.sources
    def ivyDeps = common.ivyDeps
    def artifactName = common.artifactName
  }
}

object dht extends Module with Publishing {
  def moduleDeps = List(common)
  def ivyDeps = Agg(
    Deps.bencode,
    Deps.`cats-core`,
    Deps.`cats-effect`,
    Deps.`fs2-io`,
    Deps.logstage,
  )
  object test extends TestModule
}

object bittorrent extends Module with Publishing {
  def moduleDeps = List(common, dht)
  def ivyDeps = Agg(
    Deps.bencode,
    Deps.`cats-core`,
    Deps.`cats-effect`,
    Deps.`fs2-io`,
    Deps.`monocle-core`,
    Deps.`monocle-macro`,
    Deps.logstage,
  )
  object test extends TestModule
}

object cli extends Module with NativeImageModule with ReleaseModule {
  def moduleDeps = List(bittorrent)
  def ivyDeps = Agg(
    ivy"com.monovore::decline:1.0.0",
    ivy"ch.qos.logback:logback-classic:1.2.3",
    ivy"com.lihaoyi::pprint:0.5.5",
  )
}

object protocol extends Module with Publishing {
  def moduleDeps = List(common)
  def ivyDeps = Agg(
    Deps.upickle,
    Deps.`scodec-bits`,
  )
  object js extends JsModule with Publishing {
    def moduleDeps = List(common.js)
    def sources = protocol.sources
    def ivyDeps = protocol.ivyDeps
    def artifactName = protocol.artifactName
  }
}

object server extends Module with NativeImageModule {
  def moduleDeps = List(bittorrent, protocol)
  def ivyDeps = Agg(
    ivy"org.http4s::http4s-core:${Versions.http4s}",
    ivy"org.http4s::http4s-dsl:${Versions.http4s}",
    ivy"org.http4s::http4s-blaze-server:${Versions.http4s}",
    ivy"io.7mind.izumi::logstage-adapter-slf4j:${Versions.logstage}",
    ivy"com.lihaoyi::requests:${Versions.requests}",
  )
}

trait Module extends ScalaModule with ScalafmtModule {
  def scalaVersion = "2.13.3"
  def scalacOptions = List(
    "-language:higherKinds",
    "-Ymacro-annotations",
  )
  def repositories = super.repositories ++ Seq(
      MavenRepository("https://dl.bintray.com/lavrov/maven")
  )
  def scalacPluginIvyDeps = Agg(
    ivy"org.typelevel:::kind-projector:0.11.0",
    ivy"com.olegpy::better-monadic-for:0.3.1",
  )
  trait TestModule extends Tests {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit:0.7.12",
    )
    def testFrameworks = Seq(
      "munit.Framework"
    )
  }
}

trait JsModule extends Module with scalajslib.ScalaJSModule {
  def scalaJSVersion = "1.2.0"
  import mill.scalajslib.api.ModuleKind
  def moduleKind = ModuleKind.CommonJSModule
}

trait NativeImageModule extends ScalaModule {
  private def javaHome = T.input {
    T.ctx().env.get("JAVA_HOME") match {
      case Some(homePath) => Result.Success(os.Path(homePath))
      case None => Result.Failure("JAVA_HOME env variable is undefined")
    }
  }

  private def nativeImagePath = T.input {
    val path = javaHome()/"bin"/"native-image"
    if (os exists path) Result.Success(path)
    else Result.Failure(
      "native-image is not found in java home directory.\n" +
        "Make sure JAVA_HOME points to GraalVM JDK and " +
        "native-image is set up (https://www.graalvm.org/docs/reference-manual/native-image/)"
    )
  }

  def nativeImage = T {
    import ammonite.ops._
    implicit val workingDirectory = T.ctx().dest
    %%(
      nativeImagePath(),
      "-jar", assembly().path,
      "--no-fallback",
      "--initialize-at-build-time=scala",
      "--initialize-at-build-time=scala.runtime.Statics",
      "--enable-https",
    )
    finalMainClass()
  }
}

trait Publishing extends BintrayPublishModule {
  import mill.scalalib.publish._

  def bintrayOwner = "lavrov"
  def bintrayRepo = "maven"

  def pomSettings = PomSettings(
    description = "BitTorrent",
    organization = "com.github.torrentdam",
    url = "https://github.com/TorrentDam/bittorrent",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("TorrentDam", "bittorrent"),
    developers = Seq(
      Developer("lavrov", "Vitaly Lavrov","https://github.com/lavrov")
    )
  )

  override def bintrayPublishArtifacts: T[BintrayPublishData] = T {
    val original = super.bintrayPublishArtifacts()
    original.copy(payload = original.payload.filterNot(_._2.contains("javadoc")))
  }

  def publishVersion = "0.3.0"
}

object Versions {
  val cats = "2.2.0"
  val `cats-effect` = "2.2.0"
  val fs2 = "2.4.2"
  val monocle = "2.0.0"
  val logstage = "0.10.2"
  val `scodec-bits` = "1.1.14"
  val upickle = "1.0.0"
  val http4s = "0.21.1"
  val monix = "3.2.2"
  val bencode = "0.2.0"
  val requests = "0.5.1"
}

object Deps {

  val `cats-core` = ivy"org.typelevel::cats-core::${Versions.cats}"
  val `cats-effect` = ivy"org.typelevel::cats-effect::${Versions.`cats-effect`}"

  val `fs2-io` = ivy"co.fs2::fs2-io::${Versions.fs2}"

  val `scodec-bits` = ivy"org.scodec::scodec-bits::${Versions.`scodec-bits`}"

  val logstage = ivy"io.7mind.izumi::logstage-core::${Versions.logstage}"

  val `monocle-core` = ivy"com.github.julien-truffaut::monocle-core::${Versions.monocle}"
  val `monocle-macro` = ivy"com.github.julien-truffaut::monocle-macro::${Versions.monocle}"

  val upickle = ivy"com.lihaoyi::upickle::${Versions.upickle}"

  val bencode = ivy"com.github.torrentdam::bencode::${Versions.bencode}"
}

