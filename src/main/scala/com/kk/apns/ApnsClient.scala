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

trait BaseApnsClient {
  private val prodGateway = "p"
  private val devGateway = "d"
  private var isProd = false
  private var _topic: String = ""

  val mediaType = MediaType.parse("application/json")

  def withProdGateway(b: Boolean): BaseApnsClient = {
    isProd = b
    this
  }
  def gateway = if (isProd) prodGateway else devGateway

  def withTopic(topic: String): BaseApnsClient = {
    _topic = topic
    this
  }
  def topic = _topic

  var client: OkHttpClient = new OkHttpClient()
}

trait ProviderAuthenticator { self: BaseApnsClient =>
  private var _key: String = ""
  private var _keyId: String = ""
  private var _teamId: String = ""

  def key = _key
  def keyId = _keyId
  def teamId = _teamId

  def withApnsAuthKey(key: String): BaseApnsClient = {
    _key = key
    this
  }

  def withKeyId(keyId: String): BaseApnsClient = {
    _keyId = keyId
    this
  }

  def withTeamId(teamId: String): BaseApnsClient = {
    _teamId = teamId
    this
  }
}

object Extensions {
  implicit class MapExtensions(m: Map[_, _]) {
    def flat: Map[_ , _] = m.collect {
      case (key, Some(value)) => key -> value
      case (key, v: Map[_, _]) => key -> v.flat
    }
  }
}

case class Aps(aps: Map[String, Any])
case class Notification(token: String, alert:String, title: Option[String] = None, 
    sound: Option[String] = None, category: Option[String] = None,
    badge: Option[Int] = None) {
  import org.json4s._
  import org.json4s.JsonDSL._
  import org.json4s.jackson.JsonMethods._

  def toJson = {
    val j = ("aps" -> 
                ("alert" -> 
                    ("body" -> alert) ~ ("title" -> title)) ~
                ("sound" -> sound) ~ ("badge" -> badge) ~ ("category" -> category))
                
    compact(render(j))
  }
}
    
case class NotificationResponse(responseCode: Int, responseBody: String) 

object ProviderApnsClient extends BaseApnsClient with ProviderAuthenticator {
  private val utf8 = Charset.forName("UTF-8")

  private def validateNotification(notif: Notification)(push: Notification => Future[NotificationResponse]) = {
    val d = for {
      _ <- Option(notif.token).toRight("Device Token is required").right
      _ <- Option(notif.alert).toRight("Notification Alert is required").right
    } yield notif
    
    d.fold(l => Future.failed(new IllegalArgumentException(l)), push)
  }
  
  private def validateClient(run: Unit => Future[NotificationResponse]) = {
    val d = for {
      _ <- Option(teamId).toRight("Team ID is required").right
      _ <- Option(keyId).toRight("Key ID is required").right
      _ <- Option(key).toRight("Auth Key is required").right
    } yield ()
    
    d.fold(l => Future.failed(new IllegalArgumentException(l)), run)
  }
  
  
  def push(n: Notification): Future[NotificationResponse] = validateClient { _ =>
    validateNotification(n) { notif =>
      val rb = new Request.Builder().url(s"${gateway}/3/device/${notif.token}").post(
          new RequestBody() {
            override def contentType: MediaType = {
              mediaType
            }
            override def writeTo(sink: BufferedSink) = {
              sink.write(notif.toJson.getBytes(utf8))
            }
          })
          
      val promise = Promise[NotificationResponse]()
      rb.addHeader("authorization", s"bearer $jwtToken")
      val res = client.newCall(rb.build()).enqueue(new Callback {
        override def onFailure(call: Call, e: IOException): Unit = promise.failure(e)
        override def onResponse(call: Call, response: Response): Unit = promise.success(NotificationResponse(response.code(), response.body().string()))
      })
      promise.future
    }
  }
  
  private def jwtToken = {
    val now = System.currentTimeMillis / 1000
    val header = (("alg" -> "ES256") ~ ("kid" -> keyId))
    val payload = ("iss" -> teamId, "iat" -> now)
    
    val p = Base64.getUrlEncoder.encodeToString(compact(render(header)).getBytes(utf8)) 
    s"${p}.${es256(p)}"
  }
  
  private def es256(data: String) = {
    val kf = KeyFactory.getInstance("EC")
    val keyspec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.getBytes()))
    val pk = kf.generatePrivate(keyspec)
    
    val sha = Signature.getInstance("SHA256withECDSA")
    sha.initSign(pk)
    sha.update(data.getBytes(utf8))
    
    val signed = sha.sign()
    Base64.getUrlEncoder.encodeToString(signed)
  }
}

object RequestValidator {
  
}