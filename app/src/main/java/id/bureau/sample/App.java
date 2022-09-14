package id.bureau.sample;
import android.app.Application;
import com.bugfender.sdk.Bugfender;
import com.bugfender.android.BuildConfig;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Bugfender.init(this, "xeqXnmuUIuF3AacMr7mh3DtTPXv0nnu3", BuildConfig.DEBUG);
        Bugfender.enableCrashReporting();
       // Bugfender.enableUIEventLogging(this);
       // Bugfender.enableLogcatLogging(); // optional, if you want logs automatically collected from logcat
    }
}
