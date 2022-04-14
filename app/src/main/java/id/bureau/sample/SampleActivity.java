package id.bureau.sample;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;

import com.google.gson.JsonObject;
import com.neoeyed.sdk.neoEYED;

public class SampleActivity extends AppCompatActivity implements View.OnClickListener  {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sample);
        findViewById(R.id.submitButton).setOnClickListener(this);
    }


    @Override
    public void onClick(View view) {
        EditText userIdText = findViewById(R.id.userID);
        String userId = userIdText.getText().toString();
        Log.d("typed", " typed user id: " + userId);
        JsonObject behaviour = neoEYED.dumpBehavior(userId);
        Log.d("typed", "Neo eyed behaviour: " + userId + "  " + behaviour);

    }
}