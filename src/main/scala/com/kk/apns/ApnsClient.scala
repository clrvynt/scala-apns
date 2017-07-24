package com.kk.apns

import java.io._
import scala.util._
import okhttp3._
import okio.BufferedSink
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

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
    val r = Map("aps" -> Map("alert" -> Map("body" -> alert, "title" -> title), "sound" -> sound, "badge" -> badge, "category" -> category))
    
    val j = ("aps" -> 
                ("alert" -> 
                    ("body" -> alert) ~ ("title" -> title)) ~
                ("sound" -> sound) ~ ("badge" -> badge) ~ ("category" -> category))
                
    compact(render(j))
  }
}
    
case class NotificationResponse(responseCode: Int, responseBody: String) 

object ProviderApnsClient extends BaseApnsClient with ProviderAuthenticator {
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
              sink.write(notif.toJson.getBytes("utf-8"))
            }
          })
          
      rb.addHeader("authorization", s"bearer $jwtToken")
      Future {
        NotificationResponse(100, "foo")
      }
    }
    
  }
}

object RequestValidator {
  
}