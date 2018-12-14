package com.github.lavrov.bittorrent.dht

import java.net.InetAddress

import cats._
import cats.implicits._
import cats.mtl.MonadState
import scodec.bits.ByteVector

class RoutingTable[F[_]: Monad](
    implicit
    BucketState: MonadState[F, BucketTree],
    PeerTableState: MonadState[F, Map[InfoHash, List[PeerInfo]]]
) {

  def findNode(nodeId: NodeId): F[List[NodeId]] =
    for {
      tree <- BucketState.get
    } yield {
      val bucket = BucketTree.findBucket(tree, nodeId)
      bucket.nodes.sortBy(NodeId.distance(nodeId, _))
    }

  def findPeers(infoHash: InfoHash): F[Option[List[PeerInfo]]] =
    for {
      peers <- PeerTableState.get
    }
    yield {
      peers.get(infoHash)
    }

  def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): F[Unit] =
    PeerTableState.modify { table =>
      table.updated(
        infoHash,
        peerInfo :: table.getOrElse(infoHash, Nil)
      )
    }

}

final case class InfoHash(bytes: ByteVector)

final case class PeerInfo(address: InetAddress)
