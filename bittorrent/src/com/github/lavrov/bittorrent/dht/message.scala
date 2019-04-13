package com.github.lavrov.bittorrent.dht.message

import java.net.{InetAddress, InetSocketAddress}

import cats.implicits._
import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bittorrent.dht.{NodeId, NodeInfo}
import com.github.lavrov.bencode.reader._
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import scodec.Codec
import scodec.bits.ByteVector

sealed trait Message {
  def transactionId: String
}
object Message {
  final case class QueryMessage(transactionId: String, query: Query) extends Message
  final case class ResponseMessage(transactionId: String, response: Bencode) extends Message
  final case class ErrorMessage(transactionId: String) extends Message

  implicit val NodeIdFormat: BencodeFormat[NodeId] =
    BencodeFormat.ByteVectorReader.imap(NodeId.apply)(_.bytes)

  val PingQueryFormat: BencodeFormat[Query.Ping] = (
    field[NodeId]("a")(field[NodeId]("id"))
  ).imap(qni => Query.Ping(qni))(v => v.queryingNodeId)

  val FindNodeQueryFormat: BencodeFormat[Query.FindNode] = (
    field[(NodeId, NodeId)]("a")((field[NodeId]("id") and field[NodeId]("target")))
  ).imap(tpl => Query.FindNode.tupled(tpl))(v => (v.queryingNodeId, v.target))

  implicit val InfoHashFormat = BencodeFormat.ByteVectorReader.imap(InfoHash)(_.bytes)

  val GetPeersQueryFormat: BencodeFormat[Query.GetPeers] = (
    field[(NodeId, InfoHash)]("a")((field[NodeId]("id") and field[InfoHash]("info_hash")))
  ).imap(tpl => Query.GetPeers.tupled(tpl))(v => (v.queryingNodeId, v.infoHash))

  val QueryFormat: BencodeFormat[Query] =
    field[String]("q").consume(
      {
        case "ping" => PingQueryFormat.upcast
        case "find_node" => FindNodeQueryFormat.upcast
        case "get_peers" => GetPeersQueryFormat.upcast
      }, {
        case _: Query.Ping => "ping"
        case _: Query.FindNode => "find_node"
        case _: Query.GetPeers => "get_peers"
      }
    )

  val QueryMessageFormat: BencodeFormat[Message.QueryMessage] = (
    field[String]("t"),
    QueryFormat
  ).imapN((tid, q) => QueryMessage(tid, q))(v => (v.transactionId, v.query))

  val InetSocketAddressCodec: Codec[InetSocketAddress] = {
    import scodec.codecs._
    (bytes(4) ~ bytes(2)).xmap(
      {
        case (address, port) =>
          new InetSocketAddress(
            InetAddress.getByAddress(address.toArray),
            port.toInt(signed = false)
          )
      },
      v => (ByteVector(v.getAddress.getAddress), ByteVector.fromInt(v.getPort, 2))
    )
  }

  val CompactNodeInfoCodec: Codec[List[NodeInfo]] = {
    import scodec.codecs._
    list(
      (bytes(20) ~ InetSocketAddressCodec).xmap(
        {
          case (id, address) =>
            NodeInfo(NodeId(id), address)
        },
        v => (v.id.bytes, v.address)
      )
    )
  }

  val CompactPeerInfoCodec: Codec[PeerInfo] = InetSocketAddressCodec.xmap(PeerInfo, _.address)

  val PingResponseFormat: BencodeFormat[Response.Ping] =
    field[NodeId]("id").imap(Response.Ping)(_.id)

  val NodesResponseFormat: BencodeFormat[Response.Nodes] = (
    field[NodeId]("id"),
    field[List[NodeInfo]]("nodes")(encodedString(CompactNodeInfoCodec))
  ).imapN(Response.Nodes)(v => (v.id, v.nodes))

  val PeersResponseFormat: BencodeFormat[Response.Peers] = (
    field[NodeId]("id"),
    field[List[PeerInfo]]("values")(BencodeFormat.listReader(encodedString(CompactPeerInfoCodec)))
  ).imapN(Response.Peers)(v => (v.id, v.peers))

  val ResponseMessageFormat: BencodeFormat[Message.ResponseMessage] = (
    field[String]("t"),
    field[Bencode]("r")
  ).imapN((tid, r) => ResponseMessage(tid, r))(v => (v.transactionId, v.response))

  val ErrorMessageFormat: BencodeFormat[Message.ErrorMessage] = (
    field[String]("t")
  ).imap(tid => ErrorMessage(tid))(v => v.transactionId)

  implicit val MessageFormat: BencodeFormat[Message] =
    field[String]("y").consume(
      {
        case "q" => QueryMessageFormat.upcast
        case "r" => ResponseMessageFormat.upcast
        case "e" => ErrorMessageFormat.upcast
      }, {
        case _: Message.QueryMessage => "q"
        case _: Message.ResponseMessage => "r"
        case _: Message.ErrorMessage => "e"
      }
    )
}

sealed trait Query {
  def queryingNodeId: NodeId
}
object Query {
  final case class Ping(queryingNodeId: NodeId) extends Query
  final case class FindNode(queryingNodeId: NodeId, target: NodeId) extends Query
  final case class GetPeers(queryingNodeId: NodeId, infoHash: InfoHash) extends Query
}

sealed trait Response
object Response {
  final case class Ping(id: NodeId)
  final case class Nodes(id: NodeId, nodes: List[NodeInfo]) extends Response
  final case class Peers(id: NodeId, peers: List[PeerInfo]) extends Response
}