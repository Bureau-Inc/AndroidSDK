Bureau SDK for Android
==========================================

## SDK Initialization

```Java
BureauAuth bureauAuth = new BureauAuth.Builder()
    .mode(BureauAuth.Mode.Sandbox)
    .clientId("Your Client Id")
    .build();
        //Other Options in builder are
        //timeOutInMs - total timeout will be 2 * timeOutInMs
        //callbackUrl
```

## Usage

```Java

AuthenticationStatus authenticationStatus = bureauAuth.authenticate(applicationContext,
    correlationId, msisdn);
// Possible AuthenticationStatus
// Completed("Authentication flow completed")
// NetworkUnavailable("Mobile network is not available")
// OnDifferentNetwork("Device is using a different network")
// ExceptionOnAuthenticate("Exception occurred while trying to authenticate")
// UnknownState("Unknown authentication state")
```

For an example SDK usage, please take a look [here](https://github.com/Bureau-Inc/AndroidSDK/blob/master/app/src/main/java/id/bureau/service/BureauService.java)
