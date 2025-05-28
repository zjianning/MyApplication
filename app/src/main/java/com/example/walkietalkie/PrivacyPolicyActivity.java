package com.example.walkietalkie;

import android.os.Bundle;
import android.text.Html;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.HtmlCompat;

public class PrivacyPolicyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_policy);
        
        // 启用ActionBar返回按钮
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.privacy_policy_title);
        }
        
        // 设置隐私政策内容
        TextView policyTextView = findViewById(R.id.privacy_policy_text);
        policyTextView.setText(HtmlCompat.fromHtml(
                getString(R.string.privacy_policy_content),
                HtmlCompat.FROM_HTML_MODE_LEGACY
        ));
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // 处理返回按钮点击
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
} 