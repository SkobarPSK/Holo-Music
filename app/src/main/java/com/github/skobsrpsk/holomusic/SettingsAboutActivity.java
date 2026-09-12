package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/** Настройки — раздел "О приложении". */
public class SettingsAboutActivity extends Activity {

    private static final String GITHUB_URL = "https://github.com/SkobarPSK/Holo-Music";

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.github.skobsrpsk.holomusic.util.LocaleHelper.wrap(newBase));
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_about);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings_category_about);
        }

        TextView versionText = findViewById(R.id.text_version);
        String versionName = "";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        versionText.setText(getString(R.string.about_version, versionName));

        findViewById(R.id.text_github_link).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
            }
        });

        findViewById(R.id.text_terms_of_use).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLegalDocument("terms_of_use.txt", R.string.terms_of_use);
            }
        });

        findViewById(R.id.text_privacy_policy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLegalDocument("privacy_policy.txt", R.string.privacy_policy);
            }
        });
    }

    private void openLegalDocument(String assetFile, int titleRes) {
        Intent intent = new Intent(this, LegalDocumentActivity.class);
        intent.putExtra(LegalDocumentActivity.EXTRA_ASSET_FILE, assetFile);
        intent.putExtra(LegalDocumentActivity.EXTRA_TITLE_RES, titleRes);
        startActivity(intent);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
