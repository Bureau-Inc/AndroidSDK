Bureau SDK for Android
==========================================

## Step 1. Add the JitPack repository to your build file
Add it in your root build.gradle at the end of repositories:

```Gradle
allprojects {
 repositories {
    jcenter()
    maven { url "https://jitpack.io" }
 }
}
```
**or**
if your repositories are managed from settings.gradle

```Gradle
dependencyResolutionManagement {
   repositories {
      ...
      maven { url "https://jitpack.io" }
    }
}
```

## Step 2. Add the dependency


```Java
implementation 'id.bureau:AndroidSDK:1.3.6'
```

## SDK Initialization

```Java
BureauAuth bureauAuth = new BureauAuth.Builder()
    .mode(BureauAuth.Mode.Sandbox)
    .clientId("Your Client Id")
    .build();
        //Other Options in builder are
        //timeOutInMs - total timeout
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
If authenticate() method returns the AuthenticationStatus Completed you can go ahead and wait for the callback from Bureau servers or poll the [userinfo API](https://docs.bureau.id/openapi/pin-point/tag/PinPoint/paths/~1userinfo/get/).

For an example SDK usage, please take a look [here](https://github.com/Bureau-Inc/AndroidSDK/blob/master/app/src/main/java/id/bureau/service/BureauService.java)

## Minimum Android SDK Version

LOLLIPOP || 21

## Dependencies expected

'com.squareup.okhttp3:okhttp:3.9.0' or compatible versions,
‘com.mixpanel.android:mixpanel-android:5.9.6’ 

## Manifest changes

```
<application
    android:usesCleartextTraffic="true"
```

or if you are using network config

```
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">PLEASE CONTACT BUREAU TO GET DOMAIN</domain>
    </domain-config>
</network-security-config>
```
