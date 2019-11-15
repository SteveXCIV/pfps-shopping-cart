package shop.programs

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import io.estatico.newtype.ops._
import java.util.UUID
import org.scalatest.AsyncFunSuite
import retry.RetryPolicy
import retry.RetryPolicies._
import shop.algebras._
import shop.arbitraries._
import shop.domain.auth._
import shop.domain.brand._
import shop.domain.cart._
import shop.domain.category._
import shop.domain.checkout._
import shop.domain.item._
import shop.domain.order._
import shop.effects.Background
import shop.ext.refined._
import shop.http.clients._
import scala.concurrent.duration._
import suite.PureTestSuite

final class CheckoutSpec extends PureTestSuite {

  val MaxRetries = 3

  val retryPolicy: RetryPolicy[IO] = limitRetries[IO](MaxRetries)

  def successfulClient(paymentId: PaymentId): PaymentClient[IO] =
    new PaymentClient[IO] {
      def process(userId: UserId, total: USD, card: Card): IO[PaymentId] =
        IO.pure(paymentId)
    }

  val unreachableClient: PaymentClient[IO] =
    new PaymentClient[IO] {
      def process(userId: UserId, total: USD, card: Card): IO[PaymentId] =
        IO.raiseError(PaymentError(""))
    }

  def recoveringClient(ref: Ref[IO, Int], paymentId: PaymentId): PaymentClient[IO] =
    new PaymentClient[IO] {
      def process(userId: UserId, total: USD, card: Card): IO[PaymentId] =
        ref.get.flatMap {
          case n if n == 1 => IO.pure(paymentId)
          case _           => ref.update(_ + 1) *> IO.raiseError(PaymentError(""))
        }
    }

  val failingOrders: Orders[IO] = new TestOrders {
    override def create(userId: UserId, paymentId: PaymentId, items: List[CartItem], total: USD): IO[OrderId] =
      IO.raiseError(OrderError(""))
  }

  val emptyCart: ShoppingCart[IO] = new TestCart {
    override def get(userId: UserId): IO[CartTotal] =
      IO.pure(CartTotal(List.empty, USD(0)))
  }

  def failingCart(cartTotal: CartTotal): ShoppingCart[IO] = new TestCart {
    override def get(userId: UserId): IO[CartTotal] =
      IO.pure(cartTotal)
    override def delete(userId: UserId): IO[Unit] = IO.raiseError(new Exception(""))
  }

  def successfulCart(cartTotal: CartTotal): ShoppingCart[IO] = new TestCart {
    override def get(userId: UserId): IO[CartTotal] =
      IO.pure(cartTotal)
    override def delete(userId: UserId): IO[Unit] = IO.unit
  }

  def successfulOrders(orderId: OrderId): Orders[IO] = new TestOrders {
    override def create(userId: UserId, paymentId: PaymentId, items: List[CartItem], total: USD): IO[OrderId] =
      IO.pure(orderId)
  }

  forAll { (uid: UserId, pid: PaymentId, oid: OrderId, card: Card, id: UUID) =>
    spec(s"empty cart - $id") {
      implicit val bg = shop.background.NoOp
      import shop.logger.NoOp
      new CheckoutProgram[IO](successfulClient(pid), emptyCart, successfulOrders(oid), retryPolicy)
        .checkout(uid, card)
        .attempt
        .map {
          case Left(EmptyCartError) => assert(true)
          case _                    => fail("Cart was not empty as expected")
        }
    }
  }

  forAll { (uid: UserId, oid: OrderId, ct: CartTotal, card: Card, id: UUID) =>
    spec(s"unreachable payment client - $id") {
      Ref.of[IO, List[String]](List.empty).flatMap { logs =>
        implicit val bg     = shop.background.NoOp
        implicit val logger = shop.logger.acc(logs)
        new CheckoutProgram[IO](unreachableClient, successfulCart(ct), successfulOrders(oid), retryPolicy)
          .checkout(uid, card)
          .attempt
          .flatMap {
            case Left(PaymentError(_)) =>
              logs.get.map {
                case (x :: xs) => assert(x.contains("Giving up") && xs.size == MaxRetries)
                case _         => fail(s"Expected $MaxRetries retries")
              }
            case _ => fail("Expected payment error")
          }
      }
    }
  }

  forAll { (uid: UserId, pid: PaymentId, oid: OrderId, ct: CartTotal, card: Card, id: UUID) =>
    spec(s"failing payment client succeeds after one retry - $id") {
      Ref.of[IO, List[String]](List.empty).flatMap { logs =>
        Ref.of[IO, Int](0).flatMap { ref =>
          implicit val bg     = shop.background.NoOp
          implicit val logger = shop.logger.acc(logs)
          new CheckoutProgram[IO](recoveringClient(ref, pid), successfulCart(ct), successfulOrders(oid), retryPolicy)
            .checkout(uid, card)
            .attempt
            .flatMap {
              case Right(id) =>
                logs.get.map { xs =>
                  assert(id == oid && xs.size == 1)
                }
              case Left(_) => fail("Expected Payment Id")
            }
        }
      }
    }
  }

  forAll { (uid: UserId, pid: PaymentId, ct: CartTotal, card: Card, id: UUID) =>
    spec(s"cannot create order, run in the background - $id") {
      Ref.of[IO, Int](0).flatMap { ref =>
        Ref.of[IO, List[String]](List.empty).flatMap { logs =>
          implicit val bg     = shop.background.counter(ref)
          implicit val logger = shop.logger.acc(logs)
          new CheckoutProgram[IO](successfulClient(pid), successfulCart(ct), failingOrders, retryPolicy)
            .checkout(uid, card)
            .attempt
            .flatMap {
              case Left(OrderError(_)) =>
                (ref.get, logs.get).mapN {
                  case (c, (x :: y :: xs)) =>
                    assert(
                      x.contains("Rescheduling") &&
                      y.contains("Giving up") &&
                      xs.size == MaxRetries &&
                      c == 1
                    )
                  case _ => fail(s"Expected $MaxRetries retries and reschedule")
                }
              case _ =>
                fail("Expected order error")
            }
        }
      }
    }
  }

  forAll { (uid: UserId, pid: PaymentId, oid: OrderId, ct: CartTotal, card: Card, id: UUID) =>
    spec(s"failing to delete cart does not affect checkout - $id") {
      implicit val bg = shop.background.NoOp
      import shop.logger.NoOp
      new CheckoutProgram[IO](successfulClient(pid), failingCart(ct), successfulOrders(oid), retryPolicy)
        .checkout(uid, card)
        .map { id =>
          assert(id == oid)
        }
    }
  }

  forAll { (uid: UserId, pid: PaymentId, oid: OrderId, ct: CartTotal, card: Card, id: UUID) =>
    spec(s"successful checkout - $id") {
      implicit val bg = shop.background.NoOp
      import shop.logger.NoOp
      new CheckoutProgram[IO](successfulClient(pid), successfulCart(ct), successfulOrders(oid), retryPolicy)
        .checkout(uid, card)
        .map { id =>
          assert(id == oid)
        }
    }
  }

}

protected class TestOrders() extends Orders[IO] {
  def get(userId: UserId, orderId: OrderId): IO[Option[Order]]                                     = ???
  def findBy(userId: UserId): IO[List[Order]]                                                      = ???
  def create(userId: UserId, paymentId: PaymentId, items: List[CartItem], total: USD): IO[OrderId] = ???
}

protected class TestCart() extends ShoppingCart[IO] {
  def add(userId: UserId, itemId: ItemId, quantity: Quantity): IO[Unit] = ???
  def get(userId: UserId): IO[CartTotal]                                = ???
  def delete(userId: UserId): IO[Unit]                                  = ???
  def removeItem(userId: UserId, itemId: ItemId): IO[Unit]              = ???
  def update(userId: UserId, cart: Cart): IO[Unit]                      = ???
}