package com.github.skobsrpsk.holomusic.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.util.LruCache;
import android.widget.ImageView;

/**
 * Загружает обложку альбома прямо из тегов аудиофайла (embedded picture).
 * MediaStore.Audio.Albums.ALBUM_ART устарел и на новых Android часто пуст,
 * поэтому читаем обложку сами через MediaMetadataRetriever — так делает
 * большинство сторонних плееров.
 *
 * Простой LRU-кэш в памяти, ключ — id трека + целевой размер. Раньше ключом
 * был только id трека: если список успевал закэшировать маленькую версию
 * под миниатюру (40dp), то экран "Сейчас играет" при заходе на тот же трек
 * получал из кэша ЭТУ маленькую версию вместо честного декодирования под
 * размер во весь экран — отсюда была заметно более мутная обложка на
 * экране плеера, когда включены миниатюры в списках. Размер в ключе
 * разделяет эти два случая на разные записи кэша.
 */
public class AlbumArtLoader {

    private static final int CACHE_SIZE_BYTES = 12 * 1024 * 1024; // 12 МБ
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private static String cacheKey(long songId, int targetSize) {
        return songId + "_" + targetSize;
    }

    public interface Callback {
        void onArtLoaded(Bitmap bitmap);
    }

    /**
     * Асинхронно загружает обложку и выставляет её в ImageView, если тег
     * трека (хранится в getTag()) к моменту завершения загрузки не изменился —
     * это защищает от гонки при быстрой перемотке между треками.
     */
    public static void loadInto(final long songId, final String path, final ImageView target, final int targetSize) {
        target.setTag(songId);
        final String key = cacheKey(songId, targetSize);

        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        target.setImageDrawable(null);

        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                return decodeEmbeddedArt(path, targetSize);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    cache.put(key, bitmap);
                }
                // Экран мог успеть перейти к другому треку, пока грузилась картинка.
                Object currentTag = target.getTag();
                if (currentTag instanceof Long && (Long) currentTag == songId) {
                    if (bitmap != null) {
                        target.setImageBitmap(bitmap);
                    } else {
                        target.setImageDrawable(null);
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Синхронная загрузка для использования в сервисе (уже в фоновом потоке). */
    public static Bitmap decodeEmbeddedArt(String path, int targetSize) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            byte[] art = retriever.getEmbeddedPicture();
            if (art == null) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, bounds);

            int sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetSize);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(art, 0, art.length, options);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static int calculateSampleSize(int width, int height, int targetSize) {
        int sampleSize = 1;
        while (width / sampleSize > targetSize * 2 || height / sampleSize > targetSize * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
