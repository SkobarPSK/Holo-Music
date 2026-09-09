package com.github.skobsrpsk.holomusic;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Хранилище настроек эквалайзера — отдельно от SortPrefs (тот уже большой).
 * Полосы хранятся как строка через запятую, так как их количество зависит
 * от устройства (Equalizer.getNumberOfBands()) и заранее неизвестно.
 */
public class EqualizerPrefs {

    private static final String PREFS = "holo_music_equalizer";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PRESET_INDEX = "preset_index"; // -1 = пользовательский (ручные полосы)
    private static final String KEY_BAND_LEVELS = "band_levels";
    private static final String KEY_PREAMP = "preamp";
    private static final String KEY_BASS_BOOST = "bass_boost_strength";
    private static final String KEY_LOUDNESS = "loudness_gain_mb";

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getPresetIndex(Context context) {
        return prefs(context).getInt(KEY_PRESET_INDEX, -1);
    }

    public static void setPresetIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_PRESET_INDEX, index).apply();
    }

    /** Возвращает сохранённые уровни полос, дополняя нулями, если сохранённых меньше, чем numBands. */
    public static int[] getBandLevels(Context context, int numBands) {
        int[] result = new int[numBands];
        String raw = prefs(context).getString(KEY_BAND_LEVELS, "");
        if (!raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (int i = 0; i < Math.min(numBands, parts.length); i++) {
                try {
                    result[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    public static void setBandLevels(Context context, int[] levels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(levels[i]);
        }
        prefs(context).edit().putString(KEY_BAND_LEVELS, sb.toString()).apply();
    }

    public static void setBandLevel(Context context, int band, int level, int totalBands) {
        int[] levels = getBandLevels(context, totalBands);
        if (band >= 0 && band < levels.length) {
            levels[band] = level;
            setBandLevels(context, levels);
        }
    }

    public static int getPreamp(Context context) {
        return prefs(context).getInt(KEY_PREAMP, 0);
    }

    public static void setPreamp(Context context, int mB) {
        prefs(context).edit().putInt(KEY_PREAMP, mB).apply();
    }

    public static int getBassBoostStrength(Context context) {
        return prefs(context).getInt(KEY_BASS_BOOST, 0);
    }

    public static void setBassBoostStrength(Context context, int strength) {
        prefs(context).edit().putInt(KEY_BASS_BOOST, strength).apply();
    }

    public static int getLoudnessGainMb(Context context) {
        return prefs(context).getInt(KEY_LOUDNESS, 0);
    }

    public static void setLoudnessGainMb(Context context, int mB) {
        prefs(context).edit().putInt(KEY_LOUDNESS, mB).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
