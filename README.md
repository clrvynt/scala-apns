# scala-apns
Simple Scala APNS Library that uses Apple's HTTP/2 API

## Requires ALPN
Make sure you add the alpn jar file as part of your bootclasspath of your Scala runtime's JVM ( Play framework / Spray etc ). This won't be required from Java 9 onwards. See [here](http://www.eclipse.org/jetty/documentation/current/alpn-chapter.html) for more information.

```java
-Xbootclasspath/p:<path_to_alpn_boot_jar>
```

## What you need for Provider Authentication

  * TeamID -- This is a 10 character alphanumeric code for your iOS Dev team
  * KeyID -- Create this key from the "Keys" section inside your developer account
  * Key -- This is the private key/secret that you will obtain when you create the aforementioned key
  * gateway -- "true" for production and "false" for sandbox
  * token -- This is the device token that your device gave you when it registered for push notifications with Apple
  * topic -- This is typically your bundle identifier ( com.kk.apns ) 


## Usage

```scala
val pa = ProviderApnsClientBuilder
          .withApnsAuthKey(pk).withKeyId(keyId).withTeamId(teamId).withTopic("com.kk.apns")
          .withProdGateway(gateway).build
          
pa match {
  case Success(v) =>
    client.push(Notification(token=t, alert = "Hi there! This is a push notification")
    v.push(notif).map { r => print(r.map(_.responseCode)) }
  case Failure(f) =>
    print("Exception creating client")
} 
```

## What you need for Certificate Authentication

  * Certificate -- An InputStream that represents the push certificate
  * Password -- Certificate password
  * topic -- This is typically your bundle identifier ( com.kk.apns )

## Usage

```scala
val ca = CertApnsClientBuilder
           .withCertificate(is).withPassword("foo").withProdGateway(gateway).withTopic("com.kk.apns").build
           
```


## Sending to multiple devices

```scala
val tokens = Seq("token1", "token2", "token3")
pa.map { client =>
  tokens.map { t =>
    // Push command returns a Future[Seq[NotificationResponse]] 
    client.push(Notification(token=t, alert = "Hi there! This is a push notification")).map { r =>
      r.map(print(_.responseCode))
    }
  }
}
```

  
