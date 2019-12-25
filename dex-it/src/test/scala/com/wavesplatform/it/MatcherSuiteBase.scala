package com.wavesplatform.it

import java.security.SecureRandom
import java.util.Properties

import com.google.common.primitives.Longs
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.model.MatcherModel.Normalization
import com.wavesplatform.it.MatcherSuiteBase._
import com.wavesplatform.it.sync.config.MatcherPriceAssetConfig
import com.wavesplatform.it.sync.config.MatcherPriceAssetConfig.Decimals
import com.wavesplatform.it.transactions.NodesFromDocker
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.scalatest._
import org.scalatest.concurrent.Eventually

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

abstract class MatcherSuiteBase
    extends FreeSpec
    with Matchers
    with CancelAfterFailure
    with ReportingTestName
    with NodesFromDocker
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Eventually
    with MatcherNode {

  protected implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(
    timeout = 1.minute,
    interval = 1.second
  )

  val baseFee          = 0.04.TN
  val smartFee         = 0.04.TN
  val minFee           = 0.02.waves + smartFee
  val issueFee         = 1000.TN
  val smartIssueFee    = 1000.TN + smartFee
  val leasingFee       = 0.02.TN + smartFee
  val tradeFee         = 0.04.TNs
  val smartTradeFee    = tradeFee + smartFee
  val twoSmartTradeFee = tradeFee + 2 * smartFee

  implicit class DoubleOps(value: Double) {
    val wct: Long             = Normalization.normalizeAmountAndFee(value, Decimals)
    val price: Long           = Normalization.normalizePrice(value, Decimals, Decimals)
    val waves, eth, btc: Long = Normalization.normalizeAmountAndFee(value, 8)
    val usd: Long             = Normalization.normalizePrice(value, 8, 2)
  }

  private val topicName = {
    val secureRandom            = new SecureRandom(Longs.toByteArray(Thread.currentThread().getId))
    val randomPart              = secureRandom.nextInt(Int.MaxValue)
    val maxKafkaTopicNameLength = 249
    val simplifiedClassName     = getClass.getCanonicalName.replaceAll("""(\w)\w*\.""", "$1")
    s"dex-$simplifiedClassName-${Thread.currentThread().getId}-$randomPart".take(maxKafkaTopicNameLength)
  }

  protected override def createDocker: Docker = new Docker(
    imageName = "com.wavesplatform/dex-it:latest",
    tag = getClass.getSimpleName,
    suiteConfig = baseConfig(topicName)
  )

  protected def node = dockerNodes().head

  protected def nodeConfigs: Seq[Config] = MatcherPriceAssetConfig.Configs

  override protected def beforeAll(): Unit = {
    // Hack to setup asynchttpclient settings, because we haven't own Docker class
    Map(
      "org.asynchttpclient.keepAlive"       -> "false",
      "org.asynchttpclient.maxRequestRetry" -> "0",
      "org.asynchttpclient.readTimeout"     -> "120000",
      "org.asynchttpclient.requestTimeout"  -> "120000",
      "org.asynchttpclient.ioThreadsCount"  -> "15"
    ).foreach(Function.tupled(System.setProperty))

    createKafkaTopic(topicName)
    super.beforeAll()
  }

}

object MatcherSuiteBase {
  private def baseConfig(topicName: String): Config = kafkaServer.fold(ConfigFactory.empty()) { kafkaServer =>
    ConfigFactory.parseString(s"""
         |logback.configurationFile=/opt/waves/logback-container.xml
         |
         |TN.dex.events-queue {
         |  type = kafka
         |  kafka {
         |    servers = "$kafkaServer"
         |    topic = "$topicName"
         |  }
         |}
         |TN.dex.allowed-order-versions = [1, 2, 3]
         |""".stripMargin)
  }

  private def createKafkaTopic(name: String): Unit = kafkaServer.foreach { server =>
    val properties = new Properties()
    properties.putAll(
      Map(
        "bootstrap.servers"  -> server,
        "group.id"           -> s"create-$name",
        "key.deserializer"   -> "org.apache.kafka.common.serialization.StringDeserializer",
        "value.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer"
      ).asJava)

    val adminClient = AdminClient.create(properties)
    try {
      val newTopic = new NewTopic(name, 1, 1.toShort)
      adminClient.createTopics(java.util.Collections.singletonList(newTopic))
    } finally {
      adminClient.close()
    }
  }

  private def kafkaServer: Option[String] = Option(System.getenv("KAFKA_SERVER"))
}
