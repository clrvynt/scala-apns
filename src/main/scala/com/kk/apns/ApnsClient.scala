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
import java.io.InputStream
import java.security.cert.X509Certificate
import javax.net.ssl._

trait ValidationHelpers {
    def valid(str: String, keyName: String, isEmptyAllowed: Boolean = false): Try[String] = {
    Try({
       Option(str) match {
         case Some(su) if (isEmptyAllowed || !su.trim.isEmpty) => su
         case _ => throw new IllegalArgumentException(s"$keyName is required")
       }
    })
  }
    
}

trait BaseApnsClientBuilder {
  private val prodGateway = "https://api.push.apple.com"
  private val devGateway = "https://api.development.push.apple.com"
  var isProd = false
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

  def validateClient(f: Unit => BaseApnsClient): Try[BaseApnsClient]
  def clientBuilder: BaseApnsClient
  def build: Try[BaseApnsClient] = {
    validateClient { _ =>
      clientBuilder
    }
  }
}

trait ProviderApnsClientBuilder extends BaseApnsClientBuilder with ValidationHelpers { 
  private var _key: String = null
  private var _keyId: String = null
  private var _teamId: String = null
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
    for {
      _ <- valid(teamId, "Team ID")
      _ <- valid(keyId, "Key ID")
      _ <- valid(key, "Key")
      _ <- valid(topic, "Topic")
    } yield (run(()))
  }
  
  override def clientBuilder: BaseApnsClient = {
    new ProviderApnsClient(this)
  }

}

trait CertApnsClientBuilder extends BaseApnsClientBuilder with ValidationHelpers {
  private var _cert: InputStream = null
  private var _password: String = ""
  var c: OkHttpClient = null
  
  def withCertificate(is: InputStream): CertApnsClientBuilder = {
    _cert = is
    this
  }
  def certificate = _cert
  
  def withPassword(p: String): CertApnsClientBuilder = {
    _password = p
    this
  }
  def password = _password
  
  override def validateClient(run: Unit => BaseApnsClient) = {
    for {
      _ <- validateCertificate
      _ <- valid(password, "Password", true)
      _ <- valid(topic, "Topic")
    } yield (run(()))
  }
  
  private def validateCertificate: Try[X509Certificate] = {
    Try({
      val c = cert
      c.checkValidity
      val names = c.getSubjectDN.getName.split(", ").map(s => s.split("=")).map {  case Array(f1, f2) => (f1, f2) } toMap
      
      names.get("CN") match {
        case Some(v) =>
          if (v.toLowerCase.contains("push") && 
              ( (isProd && v.toLowerCase.contains("production")) || (!isProd && v.toLowerCase.contains("development")) ) )
            v
          else 
            throw ex
        case None =>
          throw ex
      }
      c
    })
  }
  
  private def keystore = {
    val ks = KeyStore.getInstance("PKCS12");
    ks.load(certificate, password.toCharArray())
    ks
  }
  
  private def cert = keystore.getCertificate(keystore.aliases().nextElement()).asInstanceOf[X509Certificate]
  private val ex = new IllegalArgumentException("Bad certificate")

  override def clientBuilder: BaseApnsClient = {
    val ks = keystore
    val cert =  keystore.getCertificate(keystore.aliases().nextElement()).asInstanceOf[X509Certificate]
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, password.toCharArray())
    val keyManagers = kmf.getKeyManagers()
    val sslContext = SSLContext.getInstance("TLS")
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null.asInstanceOf[KeyStore])
    sslContext.init(keyManagers, tmf.getTrustManagers(), null)
    val sslSocketFactory = sslContext.getSocketFactory()
    
    val builder = new OkHttpClient.Builder()
    builder.sslSocketFactory(sslSocketFactory)
    
    c = builder.build
    null    
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

class CertApnsClient(builder: CertApnsClientBuilder) extends BaseApnsClient(builder) {

  var lastTimestamp: Long = 0
  var cachedToken: Option[String] = None
  override def call(payload: String, token: String) = createRequest(payload, token) { rb =>
    builder.c.newCall(rb.build())
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
object CertApnsClientBuilder extends CertApnsClientBuilder