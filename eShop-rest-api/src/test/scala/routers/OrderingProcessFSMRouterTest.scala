package routers

import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import org.scalatest.{FunSuiteLike, Matchers}
import actors.{DisplayOrderActor, ProductQuantityActor}
import db.populators.Seeder

import domain.models._
import domain._
import domain.models.response.FSMProcessInfoResponse

import shared.models.{Product, ProductOrderItem}

import scala.concurrent.duration._
import utils.JsonSupport

class OrderingProcessFSMRouterTest extends FunSuiteLike
  with Matchers with ScalatestRouteTest with JsonSupport {

  private implicit val routeTestTimeout = RouteTestTimeout(5.second)

  private val productOrderItem1: ProductOrderItem = ProductOrderItem(1, "iPhone")
  private val productOrderItem2: ProductOrderItem = ProductOrderItem(3, "Computer")

  private val product1: Product = productOrderItem1.toProduct
  private val product2: Product = productOrderItem2.toProduct

  private val displayOrderActor = system.actorOf(DisplayOrderActor.props, "DisplayOrderActor")
  private val productQuantityActor = system.actorOf(ProductQuantityActor.props, "ProductQuantityActor")
  private val orderingProcessFSMRouter = new OrderingProcessFSMRouter(displayOrderActor, productQuantityActor)()

  private val route = orderingProcessFSMRouter.route

  test("Ordering process FSM router") {
    val responseMessage = "adding item to shopping cart!"

    Post("/createOrder/1") ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(Idle.toString, EmptyShoppingCart.toString, "order created!")
    }
    Post("/orderId/1/addItemToShoppingCart", productOrderItem1) ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(InShoppingCart.toString, EmptyShoppingCart.toString, responseMessage)
    }
    //To ensure order of added items
    Thread.sleep(300)
    Post("/orderId/1/addItemToShoppingCart", productOrderItem2) ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(InShoppingCart.toString, NonEmptyShoppingCart(Seq(product1)).toString, responseMessage)
    }
    //To ensure order of added items
    Thread.sleep(300)
    Post("/orderId/1/addItemToShoppingCart", productOrderItem2) ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(InShoppingCart.toString, NonEmptyShoppingCart(Seq(product1, product2)).toString, responseMessage)
    }
    Post("/orderId/1/confirmShoppingCart") ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(InShoppingCart.toString, NonEmptyShoppingCart(Seq(product1, product2)).toString, "confirm shopping cart!")
    }
    Post("/orderId/1/deliveryMethod", DeliveryMethodEntity("Courier")) ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(WaitingForChoosingDeliveryMethod.toString, NonEmptyShoppingCart(Seq(product1, product2)).toString, "delivery method chosen!")
    }
    Post("/orderId/1/paymentMethod", PaymentMethodEntity("CreditCard")) ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(WaitingForChoosingPaymentMethod.toString, DataWithDeliveryMethod(NonEmptyShoppingCart(Seq(product1, product2)), DeliveryMethod.Courier).toString, "payment method chosen!")
    }
    Post("/orderId/1/checkout") ~> route ~> check {
      responseAs[FSMProcessInfoResponse] shouldEqual FSMProcessInfoResponse(OrderReadyToCheckout.toString, DataWithPaymentMethod(NonEmptyShoppingCart(Seq(product1, product2)), DeliveryMethod.Courier, PaymentMethod.CreditCard).toString, "order processed!")
    }
  }

  override protected def beforeAll(): Unit = {
    Seeder.run()
  }
}
