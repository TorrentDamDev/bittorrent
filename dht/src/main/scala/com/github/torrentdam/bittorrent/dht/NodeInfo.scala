package com.github.torrentdam.bittorrent.dht

import cats.effect.std.Random
import cats.syntax.all.*
import cats.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import scodec.bits.ByteVector

final case class NodeInfo(id: NodeId, address: SocketAddress[IpAddress])

final case class NodeId(bytes: ByteVector) {
  val int = BigInt(1, bytes.toArray)
}

object NodeId {

  private def distance(a: ByteVector, b: ByteVector): BigInt = BigInt(1, (a.xor(b)).toArray)

  def distance(a: NodeId, b: NodeId): BigInt = distance(a.bytes, b.bytes)

  def distance(a: NodeId, b: InfoHash): BigInt = distance(a.bytes, b.bytes)

  def random[F[_]](using Random[F], Monad[F]): F[NodeId] = {
    for bytes <- Random[F].nextBytes(20)
    yield NodeId(ByteVector.view(bytes))
  }
  
  def fromInt(int: BigInt): NodeId = NodeId(ByteVector.view(int.toByteArray).padTo(20))
  
  def randomInRange[F[_]](from: BigInt, until: BigInt)(using Random[F], Monad[F]): F[NodeId] =
    val range = until - from
    for
      bytes <- Random[F].nextBytes(range.bitLength / 8)
      integer = BigInt(1, bytes) + from
    yield NodeId(ByteVector.view(integer.toByteArray).padLeft(20))

  given Show[NodeId] = nodeId => s"NodeId(${nodeId.bytes.toHex})"

  val MaxValue: BigInt = BigInt(1, Array.fill(20)(0xff.toByte))
}
