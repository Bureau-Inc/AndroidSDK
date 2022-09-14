package id.bureau.service;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import id.bureau.auth.BureauAuth;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BureauService {
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public String authenticate(Context applicationContext, String correlationId, String message) {
        BureauAuth bureauAuth = new BureauAuth.Builder()
                .mode(BureauAuth.Mode.Sandbox)
                .clientId("e66c4b3f-1481-4602-81ce-41f32ffcd976")
                .build();
        String resultMessage = "";
        try {
            BureauAuth.AuthenticationStatus authenticationStatus = bureauAuth.authenticate(applicationContext, correlationId, Long.parseLong(message));
            resultMessage = correlationId + ": " + authenticationStatus.getMessage();
        } catch (RuntimeException e) {
            resultMessage = correlationId + ": " + e.getMessage();
        }
        return resultMessage;
    }

    /**
     * @param correlationId
     * @return
     */
    public String getUserInfo(String correlationId) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .writeTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build();
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("api.sandbox.bureau.id")
                .addPathSegments("v2/auth/userinfo")
                .addQueryParameter("correlationId", correlationId)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Basic base64Encode(clientId:clientSecret)")
                .get()
                .build();
        int noTries = 0;
        while (true) {
            Call call = okHttpClient.newCall(request);
            String respString = "";
            try {
                Response response = call.execute();
                if (null == response.body()) {
                    Log.i("BureauAuth", "Json body is no present in response");
                    throw new RuntimeException("Unable to proceed with the authentication");
                }
                respString = response.body().string();
                JSONObject jsonObject = new JSONObject(respString);
                int code = jsonObject.getInt("code");
                if (code == 202100) {
                    noTries++;
                    Log.i("BureauAuth", "Retrying user info get request " + noTries);
                    if (noTries == 10) {
                        return respString;
                    }
                    Thread.sleep(100);
                } else {
                    return respString;
                }
            } catch (JSONException e) {
                Log.i("BureauAuth", e.getMessage());
                return respString;
            } catch (InterruptedException e) {
                Log.i("BureauAuth", e.getMessage());
                return respString;
            } catch (IOException e) {
                Log.i("BureauAuth", e.getMessage());
                throw new RuntimeException("Unable to obtain authenticated user info");
            } catch (IllegalStateException e) {
                Log.i("BureauAuth", e.getMessage());
                throw new RuntimeException("Unable to obtain authenticated user info");
            }
        }
    }
}
