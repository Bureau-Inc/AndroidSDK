package id.bureau.auth;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.bugfender.sdk.Bugfender;
import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.moczul.ok2curl.CurlInterceptor;
import com.moczul.ok2curl.logger.Loggable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import io.requestly.android.okhttp.api.RQInterceptor;
import io.requestly.android.okhttp.api.RQCollector;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.Buffer;


public class BureauAuth {
    private final String clientId;
    private final String host;
    private final int timeoutInMs;
    private final String callbackUrl;
    private final boolean useFinalize;

    private MixpanelAPI mixpanel = null;

    private Context appContext = null;

    BureauAuth(Mode mode, String clientId, int timeoutInMs, String callbackUrl, boolean useFinalize) {
        Mode mode1;
        if (null == mode) {
            mode1 = Mode.Production;
        } else {
            mode1 = mode;
        }
        if (mode1 == Mode.Sandbox) {
            host = "api.sandbox.bureau.id";
        } else {
            host = "api.bureau.id";
        }
        this.clientId = clientId;
        if (timeoutInMs < 1) {
            this.timeoutInMs = 4 * 1000; //4sec
        } else {
            this.timeoutInMs = timeoutInMs;
        }
        callbackUrl = null == callbackUrl ? null : callbackUrl.trim();
        this.callbackUrl = null == callbackUrl ? null : callbackUrl.length() == 0 ? null : callbackUrl;
        this.useFinalize = useFinalize;

    }

    private void sendEvent(String event, String key, String value) {
        if (mixpanel != null) {
            JSONObject properties = new JSONObject();
            try {
                properties.put(key, value);
            } catch (JSONException e) {
                Log.i("BureauAuth", "JSONException");
            }
            synchronized (mixpanel) {
                mixpanel.track(event, properties);
            }
        }
    }

    private void sendEvent(String event) {

        if (mixpanel != null) {
            synchronized (mixpanel) {
                mixpanel.track(event);
            }
        }
    }

    private void setPackageName(MixpanelAPI mMixpanel, String value, boolean debuggable) {
        JSONObject properties = new JSONObject();
        try {
            properties.put("packagename", value);
            properties.put("debuggable", debuggable);
        } catch (JSONException e) {
            Log.i("BureauAuth", "JSONException");
        }
        mMixpanel.registerSuperPropertiesOnce(properties);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(s.getBytes());
            byte[] bytes = md.digest();
            StringBuilder buffer = new StringBuilder();
            for (byte aByte : bytes) {
                String tmp = Integer.toString((aByte & 0xff) + 0x100, 16).substring(1);
                buffer.append(tmp);
            }
            return buffer.toString();
        } catch (Exception e) {
            return "";
        }

    }


    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public AuthenticationStatus authenticate(Context context, final String correlationId, final long mobileNumber) {
        //setting up app context globally
        appContext = context;
//        DataUtils
        mixpanel = MixpanelAPI.getInstance(context, "6c8eb4a72b5ea2f27850ce9e99ed31d4");
        boolean isDebuggable = (0 != (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));

        synchronized (mixpanel) {
            setPackageName(mixpanel, context.getPackageName(), isDebuggable);
            mixpanel.getPeople().identify(sha256(String.valueOf(mobileNumber)));
            mixpanel.identify(sha256(String.valueOf(mobileNumber)));
            mixpanel.timeEvent("authenticate");
        }

        final AtomicInteger requestStatus = new AtomicInteger(0);
        Date startTime = new Date();

        //some devices do not support request network api call. To mitigate we first do a direct auth and then retry via RequestNetwork api
        boolean status = triggerAuthenticationFlowDirect(context, correlationId, mobileNumber, requestStatus);

        if (!status) {
            triggerAuthenticationFlowViaConnectivityManager(context, correlationId, mobileNumber, requestStatus);
        }
        waitForWorkflowCompletion(requestStatus, startTime);
        sendEvent("authenticate", "status", buildAuthenticationStatus(requestStatus).getMessage());
        return buildAuthenticationStatus(requestStatus);
    }

    private boolean triggerAuthenticationFlowDirect(Context context, String correlationId, long mobileNumber, AtomicInteger requestStatus) {
        final ConnectivityManager connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);

        //call auth directly if cellular network is
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetworkInfo = connectivityManager.getActiveNetwork();
            if (activeNetworkInfo != null) {
                NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetworkInfo);
                if (networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    // move work on worker thread and return
                    ExecutorService executorService = Executors.newFixedThreadPool(1);
                    executorService.execute(() -> {
                        try {
                            Log.i("BureauAuth", "Android M trigger Auth");
                            triggerAuthenticationFlow(correlationId, mobileNumber, activeNetworkInfo);
                            sendEvent("direct available");
                            //Set status
                            requestStatus.compareAndSet(0, 1); // 1: Completed
                        } catch (AuthenticationException e) {
                            Log.e("BureauAuth", "Android M Auth Exception");
                            requestStatus.compareAndSet(0, -3); //-3 : ExceptionOnAuthenticate
                        }
                    });
                    return true;
                }
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void triggerAuthenticationFlowViaConnectivityManager(Context context, final String correlationId, final long mobileNumber, final AtomicInteger requestStatus) {
        final ConnectivityManager connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connectivityManager.requestNetwork(networkRequest,
                    registerNetworkCallbackForOPlusDevices(correlationId, mobileNumber, connectivityManager, requestStatus), timeoutInMs);

        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
            //https://stackoverflow.com/questions/32185628/connectivitymanager-requestnetwork-in-android-6-0
            // Android M has a bug where requestNetwork never works, hence instead of requestNetwork,
            // call api directly if on cellular network else fail the authenticate request
            Log.e("BureauAuth", "Android M No network");
            requestStatus.compareAndSet(0, -2); // -2 : NetworkUnavailable
        } else {
            connectivityManager.requestNetwork(networkRequest, registerCallbackForOMinusDevices(correlationId, mobileNumber, requestStatus));
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private ConnectivityManager.NetworkCallback registerCallbackForOMinusDevices(final String correlationId, final long mobileNumber, final AtomicInteger requestStatus) {
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onUnavailable() {
                super.onUnavailable();
                requestStatus.compareAndSet(0, -2);
                sendEvent("onUnavailable");
            }

            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                try {
                    triggerAuthenticationFlow(correlationId, mobileNumber, network);
                    sendEvent("available");
                    requestStatus.compareAndSet(0, 1);
                } catch (AuthenticationException e) {
                    requestStatus.compareAndSet(0, -3);
                }
            }
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private ConnectivityManager.NetworkCallback registerNetworkCallbackForOPlusDevices(final String correlationId, final long mobileNumber, final ConnectivityManager connectivityManager, final AtomicInteger requestStatus) {
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onUnavailable() {
                super.onUnavailable();
                NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                if (activeNetworkInfo != null && activeNetworkInfo.isConnected()) {
                    requestStatus.compareAndSet(0, -1);
                }
                requestStatus.compareAndSet(0, -2);
                sendEvent("onUnavailable");
            }

            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                sendEvent("available");
                try {
                    triggerAuthenticationFlow(correlationId, mobileNumber, network);
                    requestStatus.compareAndSet(0, 1);
                } catch (AuthenticationException e) {
                    requestStatus.compareAndSet(0, -3);
                }
            }
        };
    }

    private AuthenticationStatus buildAuthenticationStatus(AtomicInteger requestStatus) {
        switch (requestStatus.get()) {
            case 1:
                return AuthenticationStatus.Completed;
            case -1:
                return AuthenticationStatus.OnDifferentNetwork;
            case -2:
                return AuthenticationStatus.NetworkUnavailable;
            case -3:
                return AuthenticationStatus.ExceptionOnAuthenticate;
            default:
                return AuthenticationStatus.UnknownState;
        }
    }

    private void waitForWorkflowCompletion(AtomicInteger requestStatus, Date startTime) {
        long maxDuration = timeoutInMs;
        while (requestStatus.get() == 0) {
            Date currentTime = new Date();
            long duration = currentTime.getTime() - startTime.getTime();
            if (duration >= maxDuration) {
                break;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void triggerAuthenticationFlow(String correlationId, long mobileNumber, Network network) {
        try {
            OkHttpClient okHttpClient = buildHttpClient(network);
            triggerInitiateFlow(correlationId, mobileNumber, okHttpClient);
            if (useFinalize) {
                triggerFinalizeFlow(correlationId, okHttpClient);
            }
        } catch (AuthenticationException e) {
            Log.i("BureauAuth", e.getMessage());
            throw e;
        } catch (IOException | RuntimeException e) {
            Log.i("BureauAuth", e.getMessage());
            throw new AuthenticationException(e.getMessage());
        }
    }

    private void triggerFinalizeFlow(String correlationId, OkHttpClient okHttpClient) throws IOException {
        HttpUrl url = buildFinalizeUrl(correlationId);
        triggerFlow(url, okHttpClient);
    }

    private void triggerInitiateFlow(String correlationId, long mobileNumber, OkHttpClient okHttpClient) throws IOException {
        HttpUrl url = buildInitiateUrl(correlationId, mobileNumber);
        triggerFlow(url, okHttpClient);
    }

    private void triggerFlow(HttpUrl url, OkHttpClient okHttpClient) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        Call call = okHttpClient.newCall(request);
        Response response = null;
        try {
            response = call.execute();
            closeResponse(response);
        } catch (IOException e) {
            closeResponse(response);
            throw new AuthenticationException("Unable to contact the server. " + e.getMessage());
        }
    }

    private void closeResponse(Response response) throws IOException {
        if (null != response && null != response.body()) {
            try {
                response.body().bytes();
                response.body().close();
            } catch (IOException e) {
                response.body().close();
                throw e;
            }
        }
    }
    public class LoggingInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            long t1 = System.nanoTime();
            Log.d("Nandeesh", String.format("--> Sending request %s on %s%n%s", request.url(), chain.connection(), request.headers()));
            Bugfender.d("Nandeesh", String.format("--> Sending request %s on %s%n%s", request.url(), chain.connection(), request.headers()));

            Buffer requestBuffer = new Buffer();
            if(request.body() != null)
            request.body().writeTo(requestBuffer);
            Log.d("Nandeesh2", requestBuffer.readUtf8());
            Bugfender.d("Nandeesh2", requestBuffer.readUtf8());

            Response response = chain.proceed(request);

            long t2 = System.nanoTime();
            Log.d("Nandeesh", String.format("<-- Received response for %s in %.1fms%n%s", response.request().url(), (t2 - t1) / 1e6d, response.headers()));

            Bugfender.d("Nandeesh", String.format("<-- Received response for %s in %.1fms%n%s", response.request().url(), (t2 - t1) / 1e6d, response.headers()));


            MediaType contentType = response.body().contentType();
            String content = response.body().string();
            Log.d("Nandeesh", content);
            Bugfender.f("Nandeesh", content);

            ResponseBody wrappedBody = ResponseBody.create(contentType, content);
            return response.newBuilder().body(wrappedBody).build();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private OkHttpClient buildHttpClient(Network network) {
      //  RQCollector collector = new RQCollector(appContext);
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        interceptor.level(HttpLoggingInterceptor.Level.BODY);
        /*RQInterceptor rqInterceptor = new RQInterceptor.Builder(appContext)
                .collector(collector)
                .build();*/

        return new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .socketFactory(network.getSocketFactory())
                .connectTimeout(timeoutInMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutInMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutInMs, TimeUnit.MILLISECONDS)
                .addInterceptor(interceptor)
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new CurlInterceptor(message -> {
                    Log.d("OKCURL", "what is curl: " + message);
                }))
              //  .addInterceptor(rqInterceptor)

//                .addInterceptor(CurlInterceptor(object : Logger {
//            override fun log(message: String) {
//                Log.v("Ok2Curl", message)
//            }
//        }))
                .build();

    }

    private HttpUrl buildInitiateUrl(String correlationId, long mobileNumber) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(host)
                .addPathSegments("v2/auth/initiate")
                .addQueryParameter("clientId", clientId)
                .addQueryParameter("correlationId", correlationId)
//                .addQueryParameter("callbackUrl", callbackUrl)
                .addQueryParameter("countryCode", "IN")
                .addQueryParameter("mobile", String.valueOf(mobileNumber))
//                .addQueryParameter("mobile", "919742238246")

                .build();
    }

    private HttpUrl buildFinalizeUrl(String correlationId) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(host)
                .addPathSegments("v2/auth/finalize")
                .addQueryParameter("clientId", clientId)
                .addQueryParameter("correlationId", correlationId)
                .build();
    }

    public enum Mode {
        Sandbox, Production
    }

    public enum AuthenticationStatus {
        Completed("Authentication flow completed"),
        NetworkUnavailable("Mobile network is not available"),
        OnDifferentNetwork("Device is using a different network"),
        ExceptionOnAuthenticate("Exception occurred while trying to authenticate"),
        UnknownState("Unknown authentication state");

        private final String message;

        AuthenticationStatus(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class Builder {
        private Mode mode;
        private String clientId;
        private int timeOutInMs;
        private String callbackUrl;
        private boolean useFinalize;

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder timeOutInMs(int timeOutInMs) {
            this.timeOutInMs = timeOutInMs;
            return this;
        }

        public Builder callbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
            return this;
        }

        public Builder useFinalize(boolean useFinalize) {
            this.useFinalize = useFinalize;
            return this;
        }

        public BureauAuth build() {
            return new BureauAuth(mode, clientId, timeOutInMs, callbackUrl, useFinalize);
        }
    }

    public static class AuthenticationException extends RuntimeException {
        private final String message;

        public AuthenticationException(String message) {
            this.message = message;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
