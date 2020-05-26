package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.implicits._
import cats.MonadError
import cats.effect.Timer
import logstage.LogIO

import scala.concurrent.duration._

object NodeBootstrap {

  def apply[F[_]](
    client: Client[F]
  )(implicit
    F: MonadError[F, Throwable],
    timer: Timer[F],
    logger: LogIO[F]
  ): F[NodeInfo] = {
    def loop: F[NodeInfo] =
      client
        .ping(BootstrapNodeAddress)
        .map(pong => NodeInfo(pong.id, BootstrapNodeAddress))
        .recoverWith {
          case e =>
            val msg = e.getMessage()
            logger.info(s"Bootstrap failed $msg $e") >> timer.sleep(5.seconds) >> loop
        }
    logger.info("Boostrapping") *> loop <* logger.info("Bootstrap complete")
  }

  val BootstrapNodeAddress = new InetSocketAddress("router.bittorrent.com", 6881)
}