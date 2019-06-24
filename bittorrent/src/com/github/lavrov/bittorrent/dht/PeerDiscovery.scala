package com.github.lavrov.bittorrent.dht

import cats.syntax.all._
import cats.instances.all._
import cats.effect.Sync
import fs2.Stream
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.message.Message
import scodec.bits.ByteVector
import java.net.InetSocketAddress
import com.github.lavrov.bittorrent.dht.message.Query
import cats.effect.concurrent.Ref
import cats.data.NonEmptyList
import com.github.lavrov.bittorrent.dht.message.Response
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.effect.Timer

import scala.concurrent.duration._
import scala.collection.immutable.ListSet

object PeerDiscovery {

  val BootstrapNode = new InetSocketAddress("router.bittorrent.com", 6881)

  val transactionId = ByteVector.encodeAscii("aa").right.get

  def start[F[_]](infoHash: InfoHash, client: Client[F])(
      implicit F: Sync[F], timer: Timer[F]
  ): F[Stream[F, PeerInfo]] = {
    import client.selfId
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      _ <- client.sendMessage(
        BootstrapNode,
        Message.QueryMessage(transactionId, Query.Ping(selfId))
      )
      m <- client.readMessage
      r <- F.fromEither(
        m match {
          case Message.ResponseMessage(transactionId, response) =>
            Message.PingResponseFormat.read(response).leftMap(e => new Exception(e))
          case other =>
            Left(new Exception(s"Got wrong message $other"))
        }
      )
      seenPeers <- Ref.of(Set.empty[PeerInfo])
      bootstrapNodeInfo = NodeInfo(r.id, BootstrapNode)
      nodesToTry <- Ref.of(ListSet(bootstrapNodeInfo))
    } yield {
      def iteration: Stream[F, PeerInfo] =
        Stream.eval(
          nodesToTry.modify(value => (value.tail, value.headOption)).flatMap {
            case Some(nodeInfo) => F.pure(nodeInfo)
            case None => timer.sleep(10.seconds).as(bootstrapNodeInfo)
          }
        )
          .evalMap { nodeInfo =>
            for {
              _ <- client.sendMessage(
                nodeInfo.address,
                Message.QueryMessage(transactionId, Query.GetPeers(selfId, infoHash))
              )
              m <- client.readMessage
              response <- F.fromEither(
                m match {
                  case Message.ResponseMessage(transactionId, bc) =>
                    val reader =
                      Message.PeersResponseFormat.read
                        .widen[Response]
                        .orElse(Message.NodesResponseFormat.read.widen[Response])
                    reader(bc).leftMap(new Exception(_))
                  case other =>
                    Left(new Exception(s"Expected response but got $other"))
                }
              )
            } yield response
          }
          .flatMap {
            case Response.Nodes(_, nodes) =>
              val nodesSorted = nodes.sortBy(n => NodeId.distance(n.id, infoHash))
              Stream.eval(
                nodesToTry.update(value => ListSet(nodesSorted: _*) ++ value)
              ) >>
              iteration
            case Response.Peers(_, peers) =>
              Stream
                .eval(
                  seenPeers
                    .modify { value =>
                      val newPeers = peers.filterNot(value)
                      (value ++ newPeers, newPeers)
                    }
                )
                .flatMap(Stream.emits) ++ iteration
            case _ =>
              iteration
          }
          .recoverWith {
            case e =>
              Stream.eval(logger.debug(e)("Failed query")) *> iteration
          }
      iteration
    }
  }
}
