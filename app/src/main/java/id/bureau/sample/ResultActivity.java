package id.bureau.sample;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import com.neoeyed.sdk.neoEYED;

public class ResultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        Intent intent = getIntent();
        String message = intent.getStringExtra(MainActivity.RESULT);

        TextView tv = findViewById(R.id.result);
        tv.setText(message);


        neoEYED.startActivity(neoEYED.ActivityLabel.registration);
        startActivity(new Intent(ResultActivity.this, SampleActivity.class));
    }
}