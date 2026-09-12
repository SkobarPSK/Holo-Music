package com.github.skobsrpsk.holomusic;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Простое хранилище настроек — сортировка, избранное, папки.
 * Один SharedPreferences файл, без БД — этого достаточно для лёгкого плеера.
 */
public class SortPrefs {

    private static final String PREFS = "holo_music_prefs";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_FAVORITES = "favorites"; // строка вида ";id1;id2;id3;"
    private static final String KEY_FOLDERS = "library_folders"; // строка вида "|/path/one|/path/two|"
    private static final String KEY_FIRST_RUN_DONE = "first_run_done";
    private static final String KEY_LANGUAGE = "language"; // "" = системный, иначе код языка ("ru", "en", ...)

    /**
     * Поддерживаемые языки ручного переключения — единственное место,
     * которое нужно расширить при добавлении нового языка (плюс сам
     * values-xx/strings.xml с переводом).
     */
    public static final String[] SUPPORTED_LANGUAGES = {"ru", "en", "es", "uk", "be", "pl", "fr", "pt-BR"};

    /** "" означает "системный" — используем локаль устройства как есть. */
    public static String getLanguage(Context context) {
        return prefs(context).getString(KEY_LANGUAGE, "");
    }

    public static void setLanguage(Context context, String languageCode) {
        // commit(), не apply(): сразу после этого вызова приложение
        // намеренно убивает свой процесс (restartApp() в
        // SettingsInterfaceActivity), чтобы применить смену языка. apply()
        // пишет на диск асинхронно и могла не успеть до System.exit() —
        // новый процесс тогда читал бы ещё старое значение с диска.
        // commit() блокируется, пока запись не завершится физически.
        prefs(context).edit().putString(KEY_LANGUAGE, languageCode == null ? "" : languageCode).commit();
    }

    public static boolean isFirstRun(Context context) {
        return !prefs(context).getBoolean(KEY_FIRST_RUN_DONE, false);
    }

    public static void markFirstRunDone(Context context) {
        prefs(context).edit().putBoolean(KEY_FIRST_RUN_DONE, true).apply();
    }

    /** Список папок, к которым ограничена библиотека. Пустой список = вся медиатека. */
    public static List<String> getLibraryFolders(Context context) {
        String raw = prefs(context).getString(KEY_FOLDERS, "|");
        List<String> result = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            if (!part.trim().isEmpty()) result.add(part);
        }
        return result;
    }

    public static void addLibraryFolder(Context context, String path) {
        List<String> folders = getLibraryFolders(context);
        if (!folders.contains(path)) {
            folders.add(path);
        }
        saveFolders(context, folders);
    }

    public static void removeLibraryFolder(Context context, String path) {
        List<String> folders = getLibraryFolders(context);
        folders.remove(path);
        saveFolders(context, folders);
    }

    private static void saveFolders(Context context, List<String> folders) {
        StringBuilder sb = new StringBuilder("|");
        for (String f : folders) {
            sb.append(f).append("|");
        }
        prefs(context).edit().putString(KEY_FOLDERS, sb.toString()).apply();
    }

    private static final String KEY_EXCLUDED_EXTENSIONS = "excluded_extensions";
    private static final String KEY_MIN_DURATION_SECONDS = "min_duration_seconds";

    /** Список расширений, которые сканирование должно пропускать (все буквы в нижнем регистре, без точки). */
    public static java.util.Set<String> getExcludedExtensions(Context context) {
        return new java.util.HashSet<>(prefs(context).getStringSet(KEY_EXCLUDED_EXTENSIONS, new java.util.HashSet<String>()));
    }

    public static void setExtensionExcluded(Context context, String ext, boolean excluded) {
        java.util.Set<String> current = getExcludedExtensions(context);
        if (excluded) current.add(ext); else current.remove(ext);
        prefs(context).edit().putStringSet(KEY_EXCLUDED_EXTENSIONS, current).apply();
    }

    public static int getMinDurationSeconds(Context context) {
        return prefs(context).getInt(KEY_MIN_DURATION_SECONDS, 0);
    }

    public static void setMinDurationSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_MIN_DURATION_SECONDS, Math.max(0, seconds)).apply();
    }

    private static final String KEY_SHOW_THUMBNAILS = "show_thumbnails_in_lists";

    /** По умолчанию выключено — декодирование обложки на каждую строку списка заметно на больших библиотеках. */
    public static boolean isShowThumbnailsInLists(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_THUMBNAILS, false);
    }

    public static void setShowThumbnailsInLists(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_SHOW_THUMBNAILS, show).apply();
    }

    private static final String KEY_SHOW_ART_LOCKSCREEN = "show_art_lockscreen";

    public static boolean isShowArtOnLockscreen(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_ART_LOCKSCREEN, true);
    }

    public static void setShowArtOnLockscreen(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_SHOW_ART_LOCKSCREEN, show).apply();
    }

    // ---------- Боковое меню: порядок и видимость разделов ----------

    private static final String KEY_DRAWER_ORDER = "drawer_order";
    private static final String KEY_DRAWER_HIDDEN = "drawer_hidden";
    private static final String KEY_DEFAULT_SCREEN = "default_screen";

    private static final DrawerSection[] DEFAULT_ORDER = DrawerSection.values();

    /** Порядок разделов меню (все шесть, скрытые остаются в списке — фильтруются отдельно). */
    public static List<DrawerSection> getDrawerOrder(Context context) {
        String raw = prefs(context).getString(KEY_DRAWER_ORDER, null);
        List<DrawerSection> result = new ArrayList<>();
        if (raw != null) {
            for (String key : raw.split(",")) {
                DrawerSection s = DrawerSection.byKey(key);
                if (s != null && !result.contains(s)) result.add(s);
            }
        }
        // Дозаполняем случайно пропущенные (например, после обновления приложения
        // с новым разделом) в конце, в порядке объявления enum.
        for (DrawerSection s : DEFAULT_ORDER) {
            if (!result.contains(s)) result.add(s);
        }
        return result;
    }

    public static void setDrawerOrder(Context context, List<DrawerSection> order) {
        StringBuilder sb = new StringBuilder();
        for (DrawerSection s : order) {
            sb.append(s.key).append(",");
        }
        prefs(context).edit().putString(KEY_DRAWER_ORDER, sb.toString()).apply();
    }

    public static java.util.Set<DrawerSection> getHiddenSections(Context context) {
        java.util.Set<String> raw = prefs(context).getStringSet(KEY_DRAWER_HIDDEN, new java.util.HashSet<String>());
        java.util.Set<DrawerSection> hidden = new java.util.HashSet<>();
        for (String key : raw) {
            DrawerSection s = DrawerSection.byKey(key);
            if (s != null) hidden.add(s);
        }
        return hidden;
    }

    public static void setSectionHidden(Context context, DrawerSection section, boolean hidden) {
        java.util.Set<DrawerSection> current = getHiddenSections(context);
        if (hidden) current.add(section); else current.remove(section);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (DrawerSection s : current) keys.add(s.key);
        prefs(context).edit().putStringSet(KEY_DRAWER_HIDDEN, keys).apply();
    }

    /** Раздел, который открывается при запуске приложения. */
    public static DrawerSection getDefaultScreen(Context context) {
        String key = prefs(context).getString(KEY_DEFAULT_SCREEN, DrawerSection.ALL_TRACKS.key);
        DrawerSection s = DrawerSection.byKey(key);
        return s != null ? s : DrawerSection.ALL_TRACKS;
    }

    public static void setDefaultScreen(Context context, DrawerSection section) {
        prefs(context).edit().putString(KEY_DEFAULT_SCREEN, section.key).apply();
    }

    public static int getSortMode(Context context) {
        return prefs(context).getInt(KEY_SORT_MODE, MediaScanner.SORT_TITLE);
    }

    public static void setSortMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_SORT_MODE, mode).apply();
    }

    public static boolean isFavorite(Context context, long songId) {
        String favs = prefs(context).getString(KEY_FAVORITES, ";");
        return favs.contains(";" + songId + ";");
    }

    public static void toggleFavorite(Context context, long songId) {
        SharedPreferences p = prefs(context);
        String favs = p.getString(KEY_FAVORITES, ";");
        String token = ";" + songId + ";";
        if (favs.contains(token)) {
            favs = favs.replace(token, ";");
        } else {
            favs = favs + songId + ";";
        }
        p.edit().putString(KEY_FAVORITES, favs).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
