package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;

import com.github.skobsrpsk.holomusic.model.Song;

import java.io.File;
import java.util.Locale;

/**
 * Общее контекстное меню трека — переиспользуется и на долгое нажатие
 * в списках, и на кнопке меню на экране "Сейчас играет".
 */
public class SongActions {

    public interface Callback {
        /** Вызывается после успешного удаления — экран должен обновить свой список. */
        void onSongDeleted();
    }

    public static void showMenu(final Activity activity, final Song song, final Callback callback) {
        final String[] items = {
                activity.getString(R.string.play_next),
                activity.getString(R.string.add_to_queue),
                activity.getString(R.string.add_to_playlist),
                activity.getString(R.string.go_to_artist),
                activity.getString(R.string.song_info),
                activity.getString(R.string.set_as_ringtone),
                activity.getString(R.string.delete_song),
        };

        new AlertDialog.Builder(activity)
                .setTitle(song.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                playNext(activity, song);
                                break;
                            case 1:
                                addToQueue(activity, song);
                                break;
                            case 2:
                                addToPlaylist(activity, song);
                                break;
                            case 3:
                                goToArtist(activity, song);
                                break;
                            case 4:
                                showInfo(activity, song);
                                break;
                            case 5:
                                setAsRingtone(activity, song);
                                break;
                            case 6:
                                confirmDelete(activity, song, callback);
                                break;
                        }
                    }
                })
                .show();
    }

    static void playNext(Activity activity, Song song) {
        if (activity instanceof BaseActivity) {
            BaseActivity base = (BaseActivity) activity;
            if (base.serviceBound) {
                base.playerService.playNext(song);
                Toast.makeText(activity, R.string.play_next, Toast.LENGTH_SHORT).show();
            }
        }
    }

    static void addToQueue(Activity activity, Song song) {
        if (activity instanceof BaseActivity) {
            BaseActivity base = (BaseActivity) activity;
            if (base.serviceBound) {
                base.playerService.addToQueueEnd(song);
                Toast.makeText(activity, R.string.add_to_queue, Toast.LENGTH_SHORT).show();
            }
        }
    }

    static void goToArtist(Activity activity, Song song) {
        Intent intent = new Intent(activity, TrackListActivity.class);
        intent.putExtra(TrackListActivity.EXTRA_MODE, TrackListActivity.MODE_ARTIST);
        intent.putExtra(TrackListActivity.EXTRA_ARTIST_ID, song.artistId);
        intent.putExtra(TrackListActivity.EXTRA_TITLE, song.artist);
        activity.startActivity(intent);
    }

    static void addToPlaylist(final Activity activity, final Song song) {
        final java.util.List<String> playlists = PlaylistStore.getPlaylistNames(activity);
        if (playlists.isEmpty()) {
            Toast.makeText(activity, R.string.no_playlists_yet, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] names = playlists.toArray(new CharSequence[0]);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.add_to_playlist)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PlaylistStore.addSongToPlaylist(activity, playlists.get(which), song.id);
                        Toast.makeText(activity, R.string.added_to_playlist, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    static void showInfo(Activity activity, Song song) {
        int totalSeconds = (int) (song.duration / 1000);
        String duration = String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);

        long fileSizeBytes = 0;
        try {
            File file = new File(song.path);
            if (file.exists()) fileSizeBytes = file.length();
        } catch (Exception ignored) {
        }

        String bitrate = estimateBitrate(fileSizeBytes, song.duration, activity);
        String size = formatFileSize(fileSizeBytes);

        String message = activity.getString(R.string.song_info_format,
                song.title, song.artist, song.album, duration, bitrate, size, song.path);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.song_info)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Точного битрейта MediaMetadataRetriever.METADATA_KEY_BITRATE не даёт
     * на старых версиях Android (появился только в API 28) — вместо
     * версийных развилок считаем оценку сами: размер файла в битах делим
     * на длительность в секундах. Не учитывает overhead контейнера/тегов,
     * но для отображения пользователю достаточно точно и работает
     * одинаково на любой версии Android.
     */
    private static String estimateBitrate(long fileSizeBytes, long durationMs, Activity activity) {
        if (fileSizeBytes <= 0 || durationMs <= 0) return "—";
        long bitsPerSecond = (fileSizeBytes * 8) / (durationMs / 1000);
        int kbps = (int) (bitsPerSecond / 1000);
        return activity.getString(R.string.bitrate_format, kbps);
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "—";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1.0) {
            return String.format(Locale.getDefault(), "%.1f МБ", mb);
        }
        double kb = bytes / 1024.0;
        return String.format(Locale.getDefault(), "%.0f КБ", kb);
    }

    static void setAsRingtone(final Activity activity, final Song song) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(activity)) {
            // Прямая установка системного рингтона требует WRITE_SETTINGS,
            // а это разрешение выдаётся только через отдельный системный
            // экран — обычный requestPermissions() тут не работает.
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.set_as_ringtone)
                    .setMessage(R.string.ringtone_permission_needed)
                    .setPositiveButton(R.string.open_settings, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                            intent.setData(Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        Uri songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
        try {
            android.media.RingtoneManager.setActualDefaultRingtoneUri(
                    activity, android.media.RingtoneManager.TYPE_RINGTONE, songUri);
            Toast.makeText(activity, R.string.ringtone_set, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, R.string.delete_failed, Toast.LENGTH_SHORT).show();
        }
    }

    static void confirmDelete(final Activity activity, final Song song, final Callback callback) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.delete_song)
                .setMessage(activity.getString(R.string.delete_song_confirm, song.title))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performDelete(activity, song, callback);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Удаление и файла, и записи в MediaStore. На Android 10+ без полного
     * доступа к файлам (см. FolderBrowserActivity/MANAGE_EXTERNAL_STORAGE)
     * ContentResolver.delete() на чужом файле может бросить SecurityException.
     * Раньше это просто ловилось и молча превращалось в общий тост "не удалось
     * удалить" — пользователь не понимал, что делать дальше. Теперь в этом
     * конкретном случае (SecurityException + разрешение реально не выдано)
     * показываем тот же диалог с переходом в настройки, что и в
     * FolderBrowserActivity, вместо того чтобы городить полноценный
     * interactive RecoverableSecurityException флоу ради не такого уж частого
     * сценария. Любая другая причина неудачи (например, файла уже нет)
     * по-прежнему остаётся обычным тостом.
     */
    private static void performDelete(final Activity activity, final Song song, final Callback callback) {
        boolean fileDeleted = false;
        try {
            File file = new File(song.path);
            if (file.exists()) {
                fileDeleted = file.delete();
            }
        } catch (Exception ignored) {
        }

        boolean mediaStoreDeleted = false;
        boolean blockedBySecurity = false;
        try {
            Uri songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
            mediaStoreDeleted = activity.getContentResolver().delete(songUri, null, null) > 0;
        } catch (SecurityException e) {
            blockedBySecurity = true;
        }

        if (fileDeleted || mediaStoreDeleted) {
            Toast.makeText(activity, R.string.song_deleted, Toast.LENGTH_SHORT).show();
            if (callback != null) callback.onSongDeleted();
            return;
        }

        boolean canRequestAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !android.os.Environment.isExternalStorageManager();
        if (blockedBySecurity && canRequestAllFilesAccess) {
            showAllFilesAccessDialog(activity);
        } else {
            Toast.makeText(activity, R.string.delete_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Тот же флоу запроса MANAGE_EXTERNAL_STORAGE, что и в FolderBrowserActivity, но с формулировкой про удаление. */
    private static void showAllFilesAccessDialog(final Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.all_files_access_title)
                .setMessage(R.string.delete_needs_all_files_access)
                .setCancelable(true)
                .setPositiveButton(R.string.open_settings, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        } catch (Exception e) {
                            // На некоторых прошивках экран для конкретного приложения отсутствует — открываем общий.
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
