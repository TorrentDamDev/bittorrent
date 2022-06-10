package com.github.lavrov.bittorrent.wire

import cats.Show.Shown
import cats.effect.std.{Queue, Semaphore}
import cats.effect.implicits.*
import cats.effect.kernel.Ref
import cats.effect.{Async, Resource, Temporal}
import cats.implicits.*
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.{Logger, StructuredLogger}
import scodec.bits.ByteVector

import scala.collection.BitSet
import scala.concurrent.duration.*
import scala.util.chaining.*

object Download {

  private val MaxParallelRequests = 10

  def apply[F[_]](
    swarm: Swarm[F],
    metadata: TorrentMetadata
  )(
    using
    F: Async[F],
    logger: StructuredLogger[F]
  ): Resource[F, PiecePicker[F]] = {
    for
      picker <- Resource.eval { PiecePicker(metadata) }
      _ <- apply(swarm, picker).background
    yield
      picker
  }

  def apply[F[_]](
    swarm: Swarm[F],
    piecePicker: PiecePicker[F]
  )(
    using
    F: Temporal[F],
    logger: StructuredLogger[F]
  ): F[Unit] =
    swarm.connected.stream
      .parEvalMapUnordered(Int.MaxValue) { connection =>
        logger.addContext(("address", connection.info.address.toString: Shown)).pipe { logger =>
          connection.interested >>
          whenUnchoked(connection)(download(connection, piecePicker))
            .recoverWith {
              case e =>
                logger.debug(s"Closing connection due to ${e.getMessage}") >>
                connection.close
            }
        }
      }
      .compile
      .drain
      .attempt
      .flatMap { result =>
        logger.error(s"Download process exited with $result")
      }

  private def download[F[_]](
    connection: Connection[F],
    pieces: PiecePicker[F]
  )(using F: Temporal[F], logger: Logger[F]): F[Unit] = {
    class Internal(
        requestQueue: Queue[F, Message.Request],
        incompleteRequests: SignallingRef[F, Set[Message.Request]],
        pickMutex: Semaphore[F],
        failureCounter: Ref[F, Int]
    ) {
      def complete(request: Message.Request, bytes: ByteVector): F[Unit] =
        F.uncancelable { _ =>
          pickMutex.permit.use { _ =>
            logger.trace(s"Complete $request") >>
            pieces.complete(request, bytes) >>
            incompleteRequests.update(_ - request) >>
            failureCounter.update(_ => 0)
          }
        }

      def fail(request: Message.Request): F[Unit] =
        F.uncancelable { _ =>
          pickMutex.permit.use { _ =>
            logger.trace(s"Fail $request") >>
            incompleteRequests.update(_ - request) >>
            pieces.unpick(request) >>
            failureCounter.updateAndGet(_ + 1).flatMap(count =>
              if (count >=5 ) F.raiseError(Error.PeerDoesNotRespond()) else F.unit
            )
          }
        }

      def sendRequest(request: Message.Request): F[Unit] =
        logger.debug(s"Request $request") >>
        connection
          .request(request)
          .timeout(5.seconds)
          .attempt
          .flatMap {
            case Right(bytes) => complete(request, bytes)
            case Left(_) => fail(request)
          }

      def fillQueue: F[Unit] =
        (incompleteRequests, connection.availability, pieces.updates).tupled.discrete
          .evalTap {
            case (requests: Set[Message.Request], availability: BitSet, _) if requests.size < MaxParallelRequests =>
              F.uncancelable { poll =>
                pickMutex.permit.use { _ =>
                  for
                    request <- pieces.pick(availability, connection.info.address)
                    _ <- request match {
                      case Some(request) =>
                        logger.trace(s"Picked $request") >>
                          incompleteRequests.update(_ + request) >>
                          requestQueue.offer(request)
                      case None =>
                        logger.trace(s"No pieces dispatched for ${connection.info.address}")
                    }
                  yield ()
                }
              }.void
            case _ =>
              F.unit
          }
          .compile
          .drain

      def drainQueue: F[Unit] =
        Stream.fromQueueUnterminated(requestQueue)
          .parEvalMapUnordered(MaxParallelRequests)(sendRequest)
          .compile
          .drain

      def run: F[Unit] =
        logger.info(s"Download started") >>
        fillQueue.race(drainQueue).void
          .guarantee {
            pickMutex.acquire >>
              incompleteRequests.get.flatMap { requests =>
                logger.debug(s"Unpick $requests") >>
                  requests.toList.traverse_(pieces.unpick)
              }
          }
    }
    for
      requestQueue <- Queue.unbounded[F, Message.Request]
      incompleteRequests <- SignallingRef[F, Set[Message.Request]](Set.empty)
      pickMutex <- Semaphore(1)
      failureCounter <- Ref.of(0)
      _ <- Internal(requestQueue, incompleteRequests, pickMutex, failureCounter).run
    yield ()
  }

  private def whenUnchoked[F[_]](connection: Connection[F])(f: F[Unit])(
    using
    F: Temporal[F],
    logger: Logger[F]
  ): F[Unit] = {
    def unchoked = connection.choked.map(!_)
    def asString(choked: Boolean) = if choked then "Choked" else "Unchoked"
    F.ref[Option[Boolean]](None)
      .flatMap { current =>
        connection.choked.discrete
          .evalTap { choked =>
            current.getAndSet(choked.some).flatMap { previous =>
              val from = previous.map(asString)
              val to = asString(choked)
              logger.debug(s"$from $to")
            }
          }
          .flatMap { choked =>
            if choked then
              Stream
                .fixedDelay(30.seconds)
                .interruptWhen(unchoked)
                .flatMap { _ =>
                  Stream.raiseError[F](Error.TimeoutWaitingForUnchoke(30.seconds))
                }
            else
              Stream
                .eval(f)
                .interruptWhen(connection.choked)
          }
          .compile
          .drain
    }
  }

  enum Error(message: String) extends Throwable(message):
    case TimeoutWaitingForUnchoke(duration: FiniteDuration) extends Error(s"Unchoke timeout $duration")
    case TimeoutWaitingForPiece(duration: FiniteDuration) extends Error(s"Block request timeout $duration")
    case InvalidChecksum() extends Error("Invalid checksum")
    case PeerDoesNotRespond() extends Error("Peer does not respond")
}
