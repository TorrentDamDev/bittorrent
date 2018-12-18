package com.github.lavrov.bittorrent.protocol.message
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import scodec.Codec
import scodec.codecs._
import scodec.bits.ByteVector

final case class Handshake(
    protocolString: String,
    infoHash: InfoHash,
    peerId: PeerId
)

object Handshake {
  val ProtocolStringCodec: Codec[String] = variableSizeBytes(uint8, utf8)
  val ReserveCodec: Codec[Unit] = constant(ByteVector.fill(8)(0))
  val InfoHashCodec: Codec[InfoHash] = bytes(20).xmap(InfoHash, _.bytes)
  val PeerIdCodec: Codec[PeerId] = bytes(20).xmap(PeerId.apply, _.bytes)
  val HandshakeCodec: Codec[Handshake] = ((ProtocolStringCodec <~ ReserveCodec) :: InfoHashCodec :: PeerIdCodec).as
}