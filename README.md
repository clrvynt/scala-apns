# scala-apns
Simple APNS repo with a Provider Authentication 

## What you need

  * TeamID -- This is a 10 character alphanumeric code for your iOS Dev team
  * KeyID -- Create this key from the "Keys" section inside your developer account
  * Key -- This is the private key/secret that you will obtain when you create the aforementioned key
  * gateway -- "true" for production and "false" for sandbox
  * token -- This is the device token that your device gave you when it registered for push notifications with Apple
  * topic -- This is typically your bundle identifier ( com.kk.apns ) 


## Usage

```scala
val pa = ProviderApnsClient
          .withApnsAuthKey(pk).withKeyId(keyId).withTeamId(teamId).withTopic("com.kk.apns")
          .withProdGateway(gateway).build
          
          
pa.map { client =>
  val notif = new Notification(token = t, alert = "Hi there! This is a push notification")
  client.push(notif).map { r =>
    print(r.responseCode)
  }
} 
```
