package id.bureau.sample;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.util.UUID;
import id.bureau.util.AsyncUtils;
import id.bureau.service.BureauService;

public class MainActivity extends AppCompatActivity {

    private BureauService bureauService = new BureauService();

    public static final String RESULT = "id.bureau.sample.RESULT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void sendMessage(View view) {
        final Intent intent = new Intent(this, ResultActivity.class);
        EditText msisdn = (EditText) findViewById(R.id.msisdn);
        final String message = msisdn.getText().toString();
        final MainActivity activity = this;
        AsyncUtils.scheduleTask(new Runnable() {
            @Override
            public void run() {
                final String correlationId = UUID.randomUUID().toString();
                final String finalResultMessage = bureauService.authenticate(getApplicationContext(), correlationId, message);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), finalResultMessage, Toast.LENGTH_LONG).show();
                    }
                });

                final String userInfo = bureauService.getUserInfo(correlationId);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        intent.putExtra(RESULT, userInfo);
                        startActivity(intent);
                    }
                });
            }
        });
    }
}