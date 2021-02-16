package id.bureau.auth;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


public class BureauAuth {
    private final Mode mode;
    private final String clientId;
    private final String host;
    private final int timeoutInMs;
    private final String callbackUrl;
    private final boolean useFinalize;

    BureauAuth(Mode mode, String clientId, int timeoutInMs, String callbackUrl, boolean useFinalize) {
        if (null == mode) {
            this.mode = Mode.Production;
        } else {
            this.mode = mode;
        }
        switch (this.mode) {
            case Sandbox:
                host = "api.sandbox.bureau.id";
                break;
            default:
                host = "api.bureau.id";
                break;
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

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public AuthenticationStatus authenticate(Context context, final String correlationId, final long mobileNumber) {
        final AtomicInteger requestStatus = new AtomicInteger(0);
        Date startTime = new Date();
        triggerAuthenticationFlowViaConnectivityManager(context, correlationId, mobileNumber, requestStatus);
        waitForWorkflowCompletion(requestStatus, startTime);
        return buildAuthenticationStatus(requestStatus);
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
            }

            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                try {
                    triggerAuthenticationFlow(correlationId, mobileNumber, network);
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
            }

            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
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
        } catch (IOException e) {
            Log.i("BureauAuth", e.getMessage());
            throw new AuthenticationException(e.getMessage());
        } catch (IllegalStateException e) {
            Log.i("BureauAuth", e.getMessage());
            throw new AuthenticationException(e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.i("BureauAuth", e.getMessage());
            throw new AuthenticationException(e.getMessage());
        } catch (SecurityException e) {
            Log.i("BureauAuth", e.getMessage());
            throw new AuthenticationException(e.getMessage());
        } catch (RuntimeException e) {
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

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private OkHttpClient buildHttpClient(Network network) {
        return new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .socketFactory(network.getSocketFactory())
                .connectTimeout(timeoutInMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutInMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutInMs, TimeUnit.MILLISECONDS)
                .build();
    }

    private HttpUrl buildInitiateUrl(String correlationId, long mobileNumber) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(host)
                .addPathSegments("v2/auth/initiate")
                .addQueryParameter("clientId", clientId)
                .addQueryParameter("correlationId", correlationId)
                .addQueryParameter("callbackUrl", callbackUrl)
                .addQueryParameter("msisdn", String.valueOf(mobileNumber))
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

        private String message;

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
        private String message;

        public AuthenticationException(String message) {
            this.message = message;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
