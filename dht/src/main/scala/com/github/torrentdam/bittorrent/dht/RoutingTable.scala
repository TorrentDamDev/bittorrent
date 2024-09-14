package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Ref
import cats.effect.Sync
import cats.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerInfo

import scala.collection.immutable.ListMap
import scodec.bits.ByteVector

import scala.annotation.tailrec

trait RoutingTable[F[_]] {

  def insert(node: NodeInfo): F[Unit]

  def findNodes(nodeId: NodeId): F[LazyList[NodeInfo]]

  def findBucket(nodeId: NodeId): F[List[NodeInfo]]

  def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): F[Unit]

  def findPeers(infoHash: InfoHash): F[Option[Iterable[PeerInfo]]]

  def allNodes: F[LazyList[RoutingTable.Node]]

  def updateGoodness(good: Set[NodeId], bad: Set[NodeId]): F[Unit]
}

object RoutingTable {

  enum TreeNode:
    case Split(center: BigInt, lower: TreeNode, higher: TreeNode)
    case Bucket(from: BigInt, until: BigInt, nodes: ListMap[NodeId, Node])
    
  case class Node(id: NodeId, address: SocketAddress[IpAddress], isGood: Boolean):
    def toNodeInfo: NodeInfo = NodeInfo(id, address)

  object TreeNode {

    def empty: TreeNode =
      TreeNode.Bucket(
        from = BigInt(0),
        until = BigInt(1, ByteVector.fill(20)(-1: Byte).toArray),
        ListMap.empty
      )
  }

  val MaxNodes = 8

  import TreeNode.*

  extension (bucket: TreeNode)
    def insert(node: NodeInfo, selfId: NodeId): TreeNode =
      bucket match
        case b @ Split(center, lower, higher) =>
          if (node.id.int < center)
            b.copy(lower = lower.insert(node, selfId))
          else
            b.copy(higher = higher.insert(node, selfId))
        case b @ Bucket(from, until, nodes) =>
          if nodes.size == MaxNodes
          then  
            if selfId.int >= from && selfId.int < until
            then
              // split the bucket because it contains the self node
              val center = (from + until) / 2
              val splitNode = 
                Split(
                  center,
                  lower = Bucket(from, center, nodes.view.filterKeys(_.int < center).to(ListMap)),
                  higher = Bucket(center, until, nodes.view.filterKeys(_.int >= center).to(ListMap))
                )
              splitNode.insert(node, selfId)
            else
              // drop one node from the bucket
              val badNode = nodes.values.find(!_.isGood)
              badNode match
                case Some(badNode) => Bucket(from, until, nodes.removed(badNode.id)).insert(node, selfId)
                case None => b
          else
            Bucket(from, until, nodes.updated(node.id, Node(node.id, node.address, isGood = true)))

    def remove(nodeId: NodeId): TreeNode =
      bucket match
        case b @ Split(center, lower, higher) =>
          if (nodeId.int < center)
            (lower.remove(nodeId), higher) match {
              case (Bucket(lowerFrom, _, nodes), finalHigher: Bucket) if nodes.isEmpty =>
                finalHigher.copy(from = lowerFrom)
              case (l, _) =>
                b.copy(lower = l)
            }
          else
            (higher.remove(nodeId), lower) match {
              case (Bucket(_, higherUntil, nodes), finalLower: Bucket) if nodes.isEmpty =>
                finalLower.copy(until = higherUntil)
              case (h, _) =>
                b.copy(higher = h)
            }
        case b @ Bucket(_, _, nodes) =>
          b.copy(nodes = nodes - nodeId)

    @tailrec
    def findBucket(nodeId: NodeId): Bucket =
      bucket match
        case Split(center, lower, higher) =>
          if (nodeId.int < center)
            lower.findBucket(nodeId)
          else
            higher.findBucket(nodeId)
        case b: Bucket => b

    def findNodes(nodeId: NodeId): LazyList[Node] =
      bucket match
        case Split(center, lower, higher) =>
          if (nodeId.int < center)
            lower.findNodes(nodeId) ++ higher.findNodes(nodeId)
          else
            higher.findNodes(nodeId) ++ lower.findNodes(nodeId)
        case b: Bucket => b.nodes.values.to(LazyList)
        
    def update(fn: Node => Node): TreeNode =
      bucket match
        case b @ Split(_, lower, higher) =>
          b.copy(lower = lower.update(fn), higher = higher.update(fn))
        case b @ Bucket(from, until, nodes) =>
          b.copy(nodes = nodes.view.mapValues(fn).to(ListMap))
          
  end extension

  def apply[F[_]: Concurrent](selfId: NodeId): F[RoutingTable[F]] =
    for {
      treeNodeRef <- Ref.of(TreeNode.empty)
      peers <- Ref.of(Map.empty[InfoHash, Set[PeerInfo]])
    } yield new RoutingTable[F] {

      def insert(node: NodeInfo): F[Unit] =
        treeNodeRef.update(_.insert(node, selfId))

      def findNodes(nodeId: NodeId): F[LazyList[NodeInfo]] =
        treeNodeRef.get.map(_.findNodes(nodeId).filter(_.isGood).map(_.toNodeInfo))

      def findBucket(nodeId: NodeId): F[List[NodeInfo]] =
        treeNodeRef.get.map(_.findBucket(nodeId).nodes.values.filter(_.isGood).map(_.toNodeInfo).toList)

      def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): F[Unit] =
        peers.update { map =>
          map.updatedWith(infoHash) {
            case Some(set) => Some(set + peerInfo)
            case None      => Some(Set(peerInfo))
          }
        }

      def findPeers(infoHash: InfoHash): F[Option[Iterable[PeerInfo]]] =
        peers.get.map(_.get(infoHash))
        
      def allNodes: F[LazyList[Node]] =
        treeNodeRef.get.map(_.findNodes(selfId))
        
      def updateGoodness(good: Set[NodeId], bad: Set[NodeId]): F[Unit] =
        treeNodeRef.update(
          _.update(node =>
            if good.contains(node.id) then node.copy(isGood = true)
            else if bad.contains(node.id) then node.copy(isGood = false)
            else node
          )
        )
    }
}