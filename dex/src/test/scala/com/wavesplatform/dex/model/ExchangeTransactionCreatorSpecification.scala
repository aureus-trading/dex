package com.wavesplatform.dex.model

import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.dex.MatcherTestData
import com.wavesplatform.dex.model.Events.OrderExecuted
import com.wavesplatform.dex.model.MatcherModel.Denormalization
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.produce
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.assets.exchange.OrderType._
import com.wavesplatform.transaction.assets.exchange.{AssetPair, ExchangeTransactionV1, ExchangeTransactionV2, OrderType}
import com.wavesplatform.{NoShrink, crypto}
import org.scalamock.scalatest.PathMockFactory
import org.scalatest._
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class ExchangeTransactionCreatorSpecification
    extends WordSpec
    with Matchers
    with MatcherTestData
    with BeforeAndAfterAll
    with PathMockFactory
    with PropertyChecks
    with NoShrink {

  private val pair = AssetPair(Waves, mkAssetId("BTC"))

  "ExchangeTransactionCreator" when {
    "SmartAccountTrading hasn't been activated yet" should {
      "create an ExchangeTransactionV1" in {

        val counter   = buy(pair, 100000, 0.0008, matcherFee = Some(2000L))
        val submitted = sell(pair, 100000, 0.0007, matcherFee = Some(1000L))

        val bc = stub[Blockchain]
        (bc.activatedFeatures _).when().returns(Map.empty).anyNumberOfTimes()

        val tc = new ExchangeTransactionCreator(bc, MatcherAccount, matcherSettings)
        val oe = OrderExecuted(LimitOrder(submitted), LimitOrder(counter), System.currentTimeMillis)

        tc.createTransaction(oe).explicitGet() shouldBe a[ExchangeTransactionV1]
      }

      "return an error" when {
        List((1, 2), (2, 1), (2, 2)).foreach {
          case (buyVersion, sellVersion) =>
            s"buyV$buyVersion and sellV$sellVersion" in {

              val counter   = buy(pair, 100000, 0.0008, matcherFee = Some(2000L), version = buyVersion.toByte)
              val submitted = sell(pair, 100000, 0.0007, matcherFee = Some(1000L), version = sellVersion.toByte)

              val bc = stub[Blockchain]
              (bc.activatedFeatures _).when().returns(Map.empty).anyNumberOfTimes()

              val tc = new ExchangeTransactionCreator(bc, MatcherAccount, matcherSettings)
              val oe = OrderExecuted(LimitOrder(submitted), LimitOrder(counter), System.currentTimeMillis)

              tc.createTransaction(oe) should produce("Smart Account Trading feature has not been activated yet")
            }
        }
      }
    }

    "SmartAccountTrading has been activated" should {
      "create an ExchangeTransactionV2" in {

        val counter   = buy(pair, 100000, 0.0008, matcherFee = Some(2000L), version = 2)
        val submitted = sell(pair, 100000, 0.0007, matcherFee = Some(1000L), version = 2)

        val bc = stub[Blockchain]
        (bc.activatedFeatures _).when().returns(Map(BlockchainFeatures.SmartAccountTrading.id -> 0)).anyNumberOfTimes()

        val tc = new ExchangeTransactionCreator(bc, MatcherAccount, matcherSettings)
        val oe = OrderExecuted(LimitOrder(submitted), LimitOrder(counter), System.currentTimeMillis)

        tc.createTransaction(oe).explicitGet() shouldBe a[ExchangeTransactionV2]
      }
    }
  }

  "ExchangeTransactionCreator" should {

    val bc = stub[Blockchain]

    (bc.activatedFeatures _)
      .when()
      .returns(Map(BlockchainFeatures.OrderV3.id -> 0, BlockchainFeatures.SmartAccountTrading.id -> 0))
      .anyNumberOfTimes()

    "calculate fees in exchange transaction which are equal to matcher fees in fully matched orders" in {

      val preconditions =
        for {
          ((_, buyOrder), (_, sellOrder)) <- orderV3PairGenerator
          orderSettings                   <- orderFeeSettingsGenerator(Some(buyOrder.matcherFeeAssetId))
        } yield (buyOrder, sellOrder, orderSettings)

      forAll(preconditions) {
        case (buyOrder, sellOrder, orderSettings) =>
          val tc = new ExchangeTransactionCreator(bc, MatcherAccount, matcherSettings)
          val oe = OrderExecuted(LimitOrder(buyOrder), LimitOrder(sellOrder), System.currentTimeMillis)
          val tx = tc.createTransaction(oe).explicitGet()

          tx.buyMatcherFee shouldBe buyOrder.matcherFee
          tx.sellMatcherFee shouldBe sellOrder.matcherFee
      }
    }

    "create valid exchange transaction when orders are matched partially" in {

      import com.wavesplatform.transaction.assets.exchange.OrderOps._

      val preconditions =
        for {
          ((_, buyOrder), (senderSell, sellOrder)) <- orderV3PairGenerator
          orderSettings                            <- orderFeeSettingsGenerator(Some(buyOrder.matcherFeeAssetId))
        } yield {

          val sellOrderWithUpdatedAmount = sellOrder.updateAmount(sellOrder.amount / 2)
          val newSignature               = crypto.sign(senderSell, sellOrderWithUpdatedAmount.bodyBytes())
          val correctedSellOrder         = sellOrderWithUpdatedAmount.updateProofs(Proofs(Seq(ByteStr(newSignature))))

          (buyOrder, correctedSellOrder, orderSettings)
        }

      forAll(preconditions) {
        case (buyOrder, sellOrder, orderSettings) =>
          val tc = new ExchangeTransactionCreator(bc, MatcherAccount, matcherSettings)
          val oe = OrderExecuted(LimitOrder(buyOrder), LimitOrder(sellOrder), System.currentTimeMillis)
          val tx = tc.createTransaction(oe)

          tx shouldBe 'right
      }
    }

    "create transactions with correct buyMatcherFee/sellMatcherFee for expensive matcherFeeAsset" when {

      val bc  = stub[Blockchain]
      val etc = new ExchangeTransactionCreator(bc, MatcherAccount, matcherSettings)

      (bc.activatedFeatures _)
        .when()
        .returns(Map(BlockchainFeatures.OrderV3.id -> 0, BlockchainFeatures.SmartAccountTrading.id -> 0))
        .anyNumberOfTimes()

      def test(submittedType: OrderType, submittedAmount: Long, submittedFee: Long, orderVersion: Int)(countersAmounts: Long*)(
          expectedSubmittedMatcherFees: Long*): Unit = {

        require(countersAmounts.length == expectedSubmittedMatcherFees.length)

        val submittedOrder = LimitOrder(
          createOrder(
            pairWavesBtc,
            submittedType,
            submittedAmount,
            0.00011131,
            matcherFee = submittedFee,
            matcherFeeAsset = mkAssetId("Very expensive asset"),
            version = orderVersion.toByte
          )
        )

        val counterOrders = countersAmounts.map { amount =>
          LimitOrder(createOrder(pairWavesBtc, submittedType.opposite, amount, 0.00011131))
        }

        val submittedDenormalizedAmount = Denormalization.denormalizeAmountAndFee(submittedOrder.amount, 8)
        val denormalizedCounterAmounts  = countersAmounts.map(Denormalization.denormalizeAmountAndFee(_, 8))
        s"S: $submittedType $submittedDenormalizedAmount, C: ${denormalizedCounterAmounts.mkString("[", ", ", "]")}" in {
          counterOrders
            .zip(expectedSubmittedMatcherFees)
            .zipWithIndex
            .foldLeft[AcceptedOrder](submittedOrder) {
              case (submitted, ((counter, expectedMatcherFee), i)) =>
                val oe = OrderExecuted(submitted, counter, System.currentTimeMillis)
                val tx = etc.createTransaction(oe).explicitGet()

                val counterAmount = Denormalization.denormalizeAmountAndFee(counter.order.amount, 8)
                withClue(s"C($i): ${counter.order.orderType} $counterAmount:\n") {
                  if (submittedType == BUY) tx.buyMatcherFee shouldBe expectedMatcherFee
                  else tx.sellMatcherFee shouldBe expectedMatcherFee
                }

                oe.submittedRemaining
            }
        }
      }

      /**
        * Consider the situation, when matcherFeeAsset is very expensive, that is 1 the smallest part of it
        * (like 1 satoshi for BTC) costs at least 0.003 TN. This means that 1 fraction of this asset
        * is enough to meet matcher's fee requirements (DynamicSettings mode, base fee = 0.003 Waves)
        *
        * In case of partial filling of the submitted order (with fee = 1 fraction of the expensive asset)
        * ExchangeTransactionCreator has to correctly calculate buyMatcherFee/sellMatcherFee. They should have non-zero values
        * after the first execution.
        *
        * But only for orders with version >= 3, because there is a proportional checks of spent fee for older versions.
        */
      (1 to 2).foreach { v =>
        s"submitted order version is $v" when {
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(100.waves)(1)
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(99.99999999.waves)(0)
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(50.waves, 50.waves)(0, 0)
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(1.waves, 120.waves)(0, 0)
          test(SELL, 100.waves, submittedFee = 5, orderVersion = v)(2.waves, 500.waves)(0, 4)
          test(SELL, 100.waves, submittedFee = 5, orderVersion = v)(2.waves, 50.waves)(0, 2)
        }
      }

      (3 to 3).foreach { v =>
        s"submitted order version is $v" when {
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(100.waves)(1)
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(99.99999999.waves)(1)
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(50.waves, 50.waves)(1, 0)
          test(BUY, 100.waves, submittedFee = 1, orderVersion = v)(1.waves, 120.waves)(1, 0)
          test(SELL, 100.waves, submittedFee = 5, orderVersion = v)(2.waves, 500.waves)(1, 4)
          test(SELL, 100.waves, submittedFee = 5, orderVersion = v)(2.waves, 50.waves)(1, 2)
        }
      }
    }
  }
}
