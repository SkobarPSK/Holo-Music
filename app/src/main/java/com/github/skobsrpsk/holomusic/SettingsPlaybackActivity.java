package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

/** Настройки — раздел "Воспроизведение": обложка на блокировке, оптимизация батареи. */
public class SettingsPlaybackActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.github.skobsrpsk.holomusic.util.LocaleHelper.wrap(newBase));
    }


    private TextView batteryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_playback);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings_category_playback);
        }

        setupLockscreenArtSection();
        setupBatteryOptimizationSection();
        setupEqualizerSection();
    }

    // ---------- Эквалайзер ----------

    private void setupEqualizerSection() {
        findViewById(R.id.btn_equalizer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsPlaybackActivity.this, SettingsEqualizerActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Пользователь мог сходить в системные настройки и там что-то
        // поменять (например, вручную снять исключение) — статус мог
        // измениться, пока экран был не виден.
        refreshBatteryOptimizationStatus();
    }

    // ---------- Обложка на экране блокировки ----------

    private void setupLockscreenArtSection() {
        Switch toggle = findViewById(R.id.switch_show_art_lockscreen);
        toggle.setChecked(SortPrefs.isShowArtOnLockscreen(this));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SortPrefs.setShowArtOnLockscreen(SettingsPlaybackActivity.this, isChecked);
            }
        });
    }

    // ---------- Оптимизация батареи ----------

    private void setupBatteryOptimizationSection() {
        batteryButton = findViewById(R.id.btn_battery_optimization);
        refreshBatteryOptimizationStatus();

        batteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    return;
                }
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    // На некоторых прошивках этот интент недоступен — молча игнорируем.
                }
            }
        });
    }

    private void refreshBatteryOptimizationStatus() {
        if (batteryButton == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryButton.setText(R.string.battery_optimization_excluded);
            batteryButton.setEnabled(false);
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean excluded = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryButton.setText(excluded ? R.string.battery_optimization_excluded : R.string.battery_optimization_not_excluded);
        batteryButton.setEnabled(!excluded);
        batteryButton.setTextColor(getResources().getColor(excluded ? R.color.text_secondary : R.color.holo_blue));
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
