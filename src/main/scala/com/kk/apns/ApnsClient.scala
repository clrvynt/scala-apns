package com.kk.apns

import java.io._
import scala.util._
import okhttp3._
import okio.BufferedSink
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import java.util.Base64
import java.nio.charset.Charset
import java.security._
import java.security.spec.PKCS8EncodedKeySpec

trait BaseApnsClientBuilder {
  private val prodGateway = "https://api.push.apple.com"
  private val devGateway = "https://api.development.push.apple.com"
  private var isProd = false
  private var _topic: String = ""
  val mediaType = MediaType.parse("application/json")
  val utf8 = Charset.forName("UTF-8")

  def withProdGateway(b: Boolean): BaseApnsClientBuilder = {
    isProd = b
    this
  }
  def gateway = if (isProd) prodGateway else devGateway

  def withTopic(topic: String): BaseApnsClientBuilder = {
    _topic = topic
    this
  }
  def topic = _topic

  def validateClient(f: Unit => BaseApnsClient): Either[IllegalArgumentException, BaseApnsClient]
  def clientBuilder: BaseApnsClient
  def build: Option[BaseApnsClient] = {
    val t = validateClient { _ =>
      clientBuilder
    }
    t.right.toOption
  }
}

trait ProviderApnsClientBuilder extends BaseApnsClientBuilder { 
  private var _key: String = ""
  private var _keyId: String = ""
  private var _teamId: String = ""
  def key = _key
  def keyId = _keyId
  def teamId = _teamId
  var c: OkHttpClient = new OkHttpClient()

  def withApnsAuthKey(key: String): ProviderApnsClientBuilder = {
    _key = key
    this
  }

  def withKeyId(keyId: String): ProviderApnsClientBuilder = {
    _keyId = keyId
    this
  }

  def withTeamId(teamId: String): ProviderApnsClientBuilder = {
    _teamId = teamId
    this
  }

  override def validateClient(run: Unit => BaseApnsClient) = {
    val d = for {
      _ <- Option(teamId).toRight("Team ID is required").right
      _ <- Option(keyId).toRight("Key ID is required").right
      _ <- Option(key).toRight("Auth Key is required").right
      _ <- Option(topic).toRight("Topic is required").right
    } yield ()

    d match {
      case Left(l)  => Left(new IllegalArgumentException(l))
      case Right(_) => Right(run(()))
    }
  }

  override def clientBuilder: BaseApnsClient = {
    new ProviderApnsClient(this)
  }

}

abstract class BaseApnsClient(builder: BaseApnsClientBuilder) {
  
  def createRequest(payload: String, token: String)(fn: Request.Builder => Call): Call = {
    val rb = new Request.Builder().url(s"${builder.gateway}/3/device/${token}").post(
      new RequestBody() {
        override def contentType: MediaType = {
          builder.mediaType
        }
        override def writeTo(sink: BufferedSink) = {
          sink.write(payload.getBytes(builder.utf8))
        }
      })
    rb.header("apns-topic", builder.topic)
    fn(rb)
  }


  def validateNotification(notif: Notification)(push: Notification => Future[NotificationResponse]) = {
    val d = for {
      _ <- Option(notif.token).toRight("Device Token is required").right
      _ <- Option(notif.alert).toRight("Notification Alert is required").right
    } yield notif

    d.fold(l => Future.failed(new IllegalArgumentException(l)), push)
  }

  def push(notification: Notification): Future[NotificationResponse] = validateNotification(notification) { notif =>
    val promise = Promise[NotificationResponse]()
    val res = call(notif.toJson, notif.token).enqueue(new Callback {
      override def onFailure(call: Call, e: IOException): Unit = promise.failure(e)
      override def onResponse(call: Call, response: Response): Unit = promise.success(NotificationResponse(response.code(), response.body().string()))
    })
    promise.future
  }

  def call(payload: String, token: String): Call
}

class ProviderApnsClient(builder: ProviderApnsClientBuilder) extends BaseApnsClient(builder) {

  var lastTimestamp: Long = 0
  var cachedToken: Option[String] = None
  override def call(payload: String, token: String) = createRequest(payload, token) { rb =>
    rb.addHeader("authorization", s"bearer $jwtToken")
    builder.c.newCall(rb.build())
  }

  private def jwtToken = {

    val now = System.currentTimeMillis / 1000
    if (cachedToken == None || now - lastTimestamp > 55 * 60 * 1000) {
      val header = (("alg" -> "ES256") ~ ("kid" -> builder.keyId))
      val payload = ("iss" -> builder.teamId) ~ ("iat" -> now)

      val p1 = enc(compact(render(header)))
      val p2 = enc(compact(render(payload)))
      val p3 = s"$p1.$p2"
      cachedToken = Some(s"${p3}.${es256(p3)}")
      lastTimestamp = now
    }
    cachedToken.get
  }

  private def enc(p: String) = Base64.getUrlEncoder.encodeToString(p.getBytes(builder.utf8))

  private def es256(data: String) = {
    val kf = KeyFactory.getInstance("EC")
    val keyspec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(builder.key.getBytes()))
    val pk = kf.generatePrivate(keyspec)

    val sha = Signature.getInstance("SHA256withECDSA")
    sha.initSign(pk)
    sha.update(data.getBytes(builder.utf8))

    val signed = sha.sign()
    Base64.getUrlEncoder.encodeToString(signed)
  }
}

case class Notification(token: String, alert: String, title: Option[String] = None,
                        sound: Option[String] = None, category: Option[String] = None,
                        badge: Option[Int] = None) {

  def toJson = {
    val j = ("aps" ->
      ("alert" ->
        ("body" -> alert) ~ ("title" -> title)) ~
        ("sound" -> sound) ~ ("badge" -> badge) ~ ("category" -> category))

    compact(render(j))
  }
}

case class NotificationResponse(responseCode: Int, responseBody: String)

object ProviderApnsClientBuilder extends ProviderApnsClientBuilder