package com.github.skobsrpsk.holomusic.util;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * Принудительная смена языка внутри приложения, независимо от языка
 * системы — без AndroidX/AppCompat (в проекте их нет), поэтому по старинке:
 * оборачиваем Context новым Configuration с нужной Locale.
 *
 * Если в настройках выбран "системный" язык (пустая строка в SortPrefs),
 * wrap() возвращает контекст как есть — тогда работает обычное
 * разрешение ресурсов Android (см. values/ = английский по умолчанию,
 * values-ru/ = русский).
 */
public class LocaleHelper {

    public static Context wrap(Context context) {
        String languageCode = com.github.skobsrpsk.holomusic.SortPrefs.getLanguage(context);
        if (languageCode == null || languageCode.isEmpty()) {
            return context; // системный — ничего не форсируем, пусть решает сама Android
        }

        Locale locale = new Locale(languageCode);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale);
        } else {
            config.locale = locale;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return context.createConfigurationContext(config);
        } else {
            // API 16 — createConfigurationContext ещё нет, откатываемся на
            // старый способ: мутируем общие Resources прямо на месте.
            context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
            return context;
        }
    }
}
