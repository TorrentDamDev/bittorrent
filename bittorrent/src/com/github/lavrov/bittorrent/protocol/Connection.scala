package com.github.lavrov.bittorrent.protocol

import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Timer}
import cats.mtl._
import cats.syntax.all._
import cats.{Monad, MonadError}
import com.github.lavrov.bittorrent.protocol.Connection.{Command, Event}
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.olegpy.meow.effects._
import fs2.{Chunk, Stream}
import fs2.concurrent.Queue
import fs2.io.tcp.Socket
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.ListSet
import scala.concurrent.duration._

trait Connection[F[_]] {
  def send(command: Command): F[Unit]
  def events: Stream[F, Event]
}

object Connection {

  case class State(
      lastMessageAt: Long = 0,
      choking: Boolean = true,
      interested: Boolean = false,
      peerChoking: Boolean = true,
      peerInterested: Boolean = false,
      bitfield: Option[BitVector] = None,
      queue: ListSet[Message.Request] = ListSet.empty,
      pending: ListSet[Message.Request] = ListSet.empty
  )

  trait Effects[F[_]] {
    def currentTime: F[Long]
    def send(message: Message): F[Unit]
    def schedule(in: FiniteDuration, msg: Command): F[Unit]
    def emit(event: Event): F[Unit]

    def state: MonadState[F, State]
    def error: MonadError[F, Throwable]
  }

  sealed trait Event

  object Event {
    case class Downloaded(request: Message.Request, bytes: ByteVector) extends Event
  }

  sealed trait Command

  object Command {
    case class PeerMessage(message: Message) extends Command
    case class SendKeepAlive() extends Command
    case class Download(request: Message.Request) extends Command
    case class CheckRequest(request: Message.Request) extends Command
  }

  class Behaviour[F[_]: Monad](
      keepAliveInterval: FiniteDuration,
      effects: Effects[F],
      logger: Logger[F],
  ) {

    def behaviour: Command => F[Unit] = {
      case Command.PeerMessage(message) => handleMessage(message)
      case Command.SendKeepAlive() => sendKeepAlive
      case Command.Download(request) => requestPiece(request)
      case Command.CheckRequest(request) => checkRequest(request)
    }

    def handleMessage(msg: Message): F[Unit] = {
      for {
        time <- effects.currentTime
        _ <- msg match {
          case Message.KeepAlive => Monad[F].unit
          case Message.Choke =>
            effects.state.modify(_.copy(peerChoking = true))
          case Message.Unchoke =>
            for {
              _ <- effects.state.modify(_.copy(peerChoking = false))
              _ <- requestPieceFromQueue
            } yield ()
          case Message.Interested =>
            effects.state.modify(_.copy(peerInterested = true))
          case Message.NotInterested =>
            effects.state.modify(_.copy(peerInterested = false))
          case piece: Message.Piece => receivePiece(piece)
          case Message.Bitfield(bytes) =>
            effects.state.modify(_.copy(bitfield = bytes.bits.some))
          case _ => Monad[F].unit
        }
        _ <- effects.state.modify(_.copy(lastMessageAt = time))
      } yield ()
    }

    def requestPieceFromQueue: F[Unit] = {
      for {
        iAmInterested <- effects.state.inspect(_.interested)
        _ <- effects.send(Message.Interested).whenA(!iAmInterested)
        _ <- effects.state.modify(_.copy(interested = true))
        state <- effects.state.get
        _ <-
          if (state.peerChoking)
            Monad[F].unit
          else
            state.queue.headOption match {
              case Some(request) =>
                for {
                  _ <- effects.state.set(
                    state.copy(
                      queue = state.queue.tail,
                      pending = state.pending + request
                    )
                  )
                  _ <- effects.send(request)
                  _ <- effects.schedule(10.seconds, Command.CheckRequest(request))
                } yield ()
              case None =>
                Monad[F].unit
            }

      } yield ()
    }

    def sendKeepAlive: F[Unit] = {
      for {
        state <- effects.state.get
        time <- effects.currentTime
        durationSinceLastMessage = (time - state.lastMessageAt).millis
        _ <- if (durationSinceLastMessage > keepAliveInterval)
          effects.send(Message.KeepAlive)
        else
          Monad[F].unit
        _ <- effects.schedule(keepAliveInterval, Command.SendKeepAlive())
      } yield ()
    }

    def requestPiece(request: Message.Request): F[Unit] = {
      for {
        state <- effects.state.get
        _ <- effects.state.set(state.copy(queue = state.queue + request))
        _ <- requestPieceFromQueue
      } yield ()
    }

    def checkRequest(request: Message.Request): F[Unit] = {
      for {
        stillPending <- effects.state.inspect(s => s.pending.contains(request) || s.queue.contains(request))
        _ <- if (stillPending) effects.error.raiseError[Unit](new Exception("Peer doesn't respond")) else Monad[F].unit
      } yield ()
    }

    def receivePiece(piece: Message.Piece): F[Unit] = {
      for {
        state <- effects.state.get
        request = Message.Request(piece.index, piece.begin, piece.bytes.length)
        inPending = state.pending.contains(request)
        _ <- {
          if (inPending)
            for {
              _ <- effects.emit(Event.Downloaded(request, piece.bytes))
              _ <- effects.state.set(
                state.copy(
                  pending = state.pending.filterNot(_ == request)
                )
              )
              _ <- requestPieceFromQueue
            } yield ()
          else
            effects.error.raiseError(new Exception("Unexpected piece"))
        }: F[Unit]
      } yield ()
    }
  }

  def connect[F[_]: Concurrent](
      selfId: PeerId,
      peerInfo: PeerInfo,
      infoHash: InfoHash,
      socket: Socket[F],
      timer: Timer[F]
  ): F[Connection[F]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      ops = new ConnectionOps(socket, logger)
      _ <- ops.handshake(selfId, infoHash)
      queue <- Queue.unbounded[F, Command]
      eventQueue <- Queue.noneTerminated[F, Event]
      stateRef <- Ref.of[F, State](State())
      effects = new Effects[F] {
        def currentTime: F[Long] =
          timer.clock.realTime(TimeUnit.MILLISECONDS)

        def send(message: Message): F[Unit] =
          ops.send(message)

        def schedule(in: FiniteDuration, msg: Command): F[Unit] =
          Concurrent[F].start(timer.sleep(in) *> queue.enqueue1(msg)).void

        def emit(event: Event): F[Unit] =
          eventQueue.enqueue1(event.some)

        val state: MonadState[F, State] = stateRef.stateInstance
        val error: MonadError[F, Throwable] = implicitly
      }
      enqueueFiber <- Concurrent[F] start Stream
        .repeatEval(ops.receive)
        .map(Command.PeerMessage)
        .to(queue.enqueue)
        .compile
        .drain
      behaviour = new Behaviour(10.seconds, effects, logger).behaviour
      dequeueFiber <- Concurrent[F] start {
        queue.dequeue.evalTap(behaviour).compile.drain
      }
      _ <- Concurrent[F].start {
        Concurrent[F]
          .race(enqueueFiber.join, dequeueFiber.join)
          .onError {
            case e =>
              logger.info(s"Disconnected $peerInfo") *>
              logger.debug(e)(s"Connection error") *>
              eventQueue.enqueue1(None)
          }
      }
    } yield new Connection[F] {
      def send(msg: Command): F[Unit] = queue.enqueue1(msg)

      def events: Stream[F, Event] = eventQueue.dequeue
    }
  }
}

class ConnectionOps[F[_]: Concurrent](socket: Socket[F], logger: Logger[F]) {
  private val M: MonadError[F, Throwable] = implicitly

  def handshake(selfId: PeerId, infoHash: InfoHash): F[Handshake] = {
    val message = Handshake(infoHash, selfId)
    for {
      _ <- logger.debug(s"Initiate handshake")
      _ <- socket.write(
        bytes = Chunk.byteVector(Handshake.HandshakeCodec.encode(message).require.toByteVector),
        timeout = Some(5.seconds)
      )
      maybeBytes <- socket.readN(Handshake.HandshakeCodec.sizeBound.exact.get.toInt / 8, timeout = Some(5.seconds))
      bytes <- M.fromOption(
        maybeBytes,
        new Exception("Connection was closed unexpectedly")
      )
      bv = ByteVector(bytes.toArray)
      response <- M.fromTry(
        Handshake.HandshakeCodec
          .decodeValue(bv.toBitVector)
          .toTry
      )
      _ <- logger.debug(s"Successful handshake")
    } yield response
  }

  def send(message: Message): F[Unit] =
    for {
      _ <- socket.write(Chunk.byteVector(Message.MessageCodec.encode(message).require.toByteVector))
      _ <- logger.debug(s"Sent $message")
    } yield ()

  def receive: F[Message] =
    for {
      maybeChunk <- socket.readN(4)
      chunk <- M.fromOption(maybeChunk, new Exception("Connection was closed unexpectedly"))
      size <- M fromTry Message.MessageSizeCodec.decodeValue(BitVector(chunk.toArray)).toTry
      maybeChunk <- socket.readN(size.toInt)
      chunk <- M.fromOption(maybeChunk, new Exception("Connection was closed unexpectedly"))
      bv = ByteVector(chunk.toArray)
      message <- M.fromTry(
        Message.MessageBodyCodec
          .decodeValue(bv.toBitVector)
          .toTry
      )
      _ <- logger.debug(s"Received $message")
    } yield message

}
