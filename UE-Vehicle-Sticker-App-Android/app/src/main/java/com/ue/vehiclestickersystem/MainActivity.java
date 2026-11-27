package com.ue.vehiclestickersystem;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private boolean showPass = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#8A0909"));
        setContentView(R.layout.activity_main);

        EditText txtEmail = findViewById(R.id.txtEmail), txtPassword = findViewById(R.id.txtPassword);
        ImageView btnToggle = findViewById(R.id.btnTogglePassword);
        CheckBox chkRemember = findViewById(R.id.chkRememberMe);
        Button btnSignIn = findViewById(R.id.btnSignIn);

        SharedPreferences prefs = getSharedPreferences("ue_login_prefs", MODE_PRIVATE);
        String saved = prefs.getString("saved_email", "");
        if (!saved.isEmpty()) { txtEmail.setText(saved); chkRemember.setChecked(true); }

        btnToggle.setOnClickListener(v -> {
            showPass = !showPass;
            txtPassword.setInputType(InputType.TYPE_CLASS_TEXT | (showPass
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            btnToggle.setAlpha(showPass ? 1f : 0.6f);
            txtPassword.setSelection(txtPassword.getText().length());
        });

        findViewById(R.id.txtForgotPassword).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Forgot Password")
                .setMessage("Please visit the UE Gate 1 Security Office (Window 2) or contact the UE IT Helpdesk with your Student/Employee ID to reset your password.")
                .setPositiveButton("OK", null).show());

        btnSignIn.setOnClickListener(v -> {
            String email = txtEmail.getText().toString().trim(), pass = txtPassword.getText().toString().trim();
            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please enter your email and password.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (chkRemember.isChecked()) prefs.edit().putString("saved_email", email).apply();
            else prefs.edit().remove("saved_email").apply();

            btnSignIn.setEnabled(false);
            btnSignIn.setText("SIGNING IN...");
            ApiClient.evictConnections();
            ApiClient.login(email, pass, (user, err) -> {
                btnSignIn.setEnabled(true);
                btnSignIn.setText("LOGIN");
                if (user != null) {
                    ApiClient.currentUser = user;
                    Toast.makeText(this, "Welcome, " + user.name, Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, PortalActivity.class));
                    finish();
                } else {
                    Toast.makeText(this, err != null ? err : "Login failed", Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
