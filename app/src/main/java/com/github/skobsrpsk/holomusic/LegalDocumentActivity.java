package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Показывает текстовый файл из assets — используется для "Условий
 * использования" и "Политики конфиденциальности" из раздела "О приложении".
 * Ничего сложнее ScrollView + TextView не нужно, это просто текст.
 */
public class LegalDocumentActivity extends Activity {

    public static final String EXTRA_ASSET_FILE = "asset_file";
    public static final String EXTRA_TITLE_RES = "title_res";

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.github.skobsrpsk.holomusic.util.LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_legal_document);

        int titleRes = getIntent().getIntExtra(EXTRA_TITLE_RES, R.string.app_name);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(titleRes);
        }

        String assetFile = getIntent().getStringExtra(EXTRA_ASSET_FILE);
        TextView textView = findViewById(R.id.text_document_body);
        textView.setText(readAsset(assetFile));
    }

    private String readAsset(String fileName) {
        if (fileName == null) return "";
        StringBuilder sb = new StringBuilder();
        InputStream in = null;
        try {
            in = getAssets().open(fileName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return sb.toString();
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
