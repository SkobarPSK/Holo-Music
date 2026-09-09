package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран эквалайзера. Работает "вживую", только если сервис сейчас реально
 * воспроизводит что-то (тогда есть настоящий audioSessionId и созданные
 * системные эффекты) — иначе значения просто сохраняются в EqualizerPrefs
 * и применятся при следующем запуске трека. Число полос, их частоты и
 * диапазон уровней берутся с реального устройства через PlayerService,
 * а не жёстко заданы — на разных телефонах эквалайзер отличается.
 *
 * Это настроечный экран — мини-плеер тут намеренно не показываем (как и на
 * остальных экранах настроек), поэтому подключение к сервису сделано
 * напрямую, без наследования BaseActivity.
 */
public class SettingsEqualizerActivity extends Activity implements PlaybackListener {

    private PlayerService playerService;
    private boolean serviceBound = false;
    private boolean activityStarted = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerService = ((PlayerService.PlayerBinder) service).getService();
            serviceBound = true;
            if (activityStarted) {
                playerService.setListener(SettingsEqualizerActivity.this);
                refreshAvailability();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private LinearLayout bandContainer;
    private LinearLayout equalizerContent;
    private TextView unavailableText;
    private Switch enabledSwitch;
    private Spinner presetSpinner;
    private SeekBar preampSeek;
    private SeekBar bassBoostSeek;
    private SeekBar loudnessSeek;

    private boolean suppressPresetCallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_equalizer);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings_category_equalizer);
        }

        equalizerContent = findViewById(R.id.equalizer_content);
        unavailableText = findViewById(R.id.text_equalizer_unavailable);
        bandContainer = findViewById(R.id.band_container);
        enabledSwitch = findViewById(R.id.switch_equalizer_enabled);
        presetSpinner = findViewById(R.id.spinner_preset);
        preampSeek = findViewById(R.id.seek_preamp);
        bassBoostSeek = findViewById(R.id.seek_bass_boost);
        loudnessSeek = findViewById(R.id.seek_loudness);

        enabledSwitch.setChecked(EqualizerPrefs.isEnabled(this));
        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (serviceBound) {
                    playerService.setEqualizerEnabled(isChecked);
                } else {
                    EqualizerPrefs.setEnabled(SettingsEqualizerActivity.this, isChecked);
                }
                refreshAvailability();
            }
        });

        setupBassBoost();
        setupLoudness();

        refreshAvailability();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityStarted = false;
        if (serviceBound) {
            playerService.clearListener(this);
            unbindService(connection);
            serviceBound = false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Эквалайзер (число полос/частоты/диапазон) доступен только пока
     * playerService реально держит живой Equalizer, то есть что-то играло
     * хотя бы раз в этой сессии. Если сервис не связан или ничего не
     * играло — показываем сообщение вместо полос, которые зависят от
     * конкретного устройства и без живого Equalizer прочитать нельзя.
     */
    private void refreshAvailability() {
        boolean available = serviceBound && playerService.isEqualizerAvailable();
        equalizerContent.setVisibility(available ? View.VISIBLE : View.GONE);
        unavailableText.setVisibility(available ? View.GONE : View.VISIBLE);
        if (available) {
            setupPresetSpinner();
            setupBands();
            setupPreamp();
        }
    }

    @Override
    public void onTrackChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Момент, когда сервис впервые реально начинает играть (или
                // переключает трек) — самый надёжный триггер перепроверить,
                // появился ли уже живой Equalizer, если экран открыли раньше,
                // чем начали воспроизведение.
                refreshAvailability();
            }
        });
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
    }

    // ---------- Пресеты ----------

    private void setupPresetSpinner() {
        String[] deviceNames = playerService.getEqualizerPresetNames();
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.equalizer_custom_preset));
        for (String n : deviceNames) names.add(n);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);

        int currentPreset = EqualizerPrefs.getPresetIndex(this);
        suppressPresetCallback = true;
        presetSpinner.setSelection(currentPreset + 1); // 0 = "Пользовательский", далее пресеты устройства
        suppressPresetCallback = false;

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressPresetCallback) return;
                int presetIndex = position - 1; // -1 = пользовательский
                if (presetIndex >= 0) {
                    playerService.applyPreset(presetIndex);
                    setupBands(); // подтягиваем реальные уровни, выставленные пресетом
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // ---------- Полосы ----------

    private void setupBands() {
        bandContainer.removeAllViews();
        final int bands = playerService.getNumberOfBands();
        final short[] range = playerService.getBandLevelRange();
        final int[] storedLevels = EqualizerPrefs.getBandLevels(this, bands);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < bands; i++) {
            final int bandIndex = i;
            View row = inflater.inflate(R.layout.list_item_eq_band, bandContainer, false);
            TextView freqText = row.findViewById(R.id.text_band_freq);
            SeekBar seekBar = row.findViewById(R.id.seek_band);

            int freqHz = playerService.getBandFrequencyHz(i);
            freqText.setText(freqHz >= 1000 ? (freqHz / 1000) + "к" : String.valueOf(freqHz));

            int span = range[1] - range[0];
            seekBar.setMax(span);
            seekBar.setProgress(storedLevels[i] - range[0]);

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int level = progress + range[0];
                    playerService.setBandLevel(bandIndex, level);
                    suppressPresetCallback = true;
                    presetSpinner.setSelection(0);
                    suppressPresetCallback = false;
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            bandContainer.addView(row);
        }
    }

    // ---------- Преамп ----------

    private void setupPreamp() {
        short[] range = playerService.getBandLevelRange();
        int span = range[1] - range[0];
        preampSeek.setMax(span);
        preampSeek.setProgress(EqualizerPrefs.getPreamp(this) - range[0]);

        final int rangeMin = range[0];
        preampSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                playerService.setPreamp(progress + rangeMin);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    // ---------- Бас и громкость ----------

    private void setupBassBoost() {
        bassBoostSeek.setProgress(EqualizerPrefs.getBassBoostStrength(this));
        bassBoostSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (serviceBound) playerService.setBassBoostStrength(progress);
                else EqualizerPrefs.setBassBoostStrength(SettingsEqualizerActivity.this, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void setupLoudness() {
        loudnessSeek.setProgress(EqualizerPrefs.getLoudnessGainMb(this));
        loudnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (serviceBound) playerService.setLoudnessGain(progress);
                else EqualizerPrefs.setLoudnessGainMb(SettingsEqualizerActivity.this, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }
}
