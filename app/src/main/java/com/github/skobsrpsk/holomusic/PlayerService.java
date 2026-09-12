package com.github.skobsrpsk.holomusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.AlbumArtLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервис фонового воспроизведения. Один MediaPlayer, простая очередь треков
 * в статических полях — без ContentProvider/БД, чтобы не усложнять.
 *
 * На API 21+ подключаем android.media.session.MediaSession и уведомление
 * в стиле Notification.MediaStyle — система сама рисует обложку, компактные
 * кнопки и (на новых Android) прогресс-бар. На более старых версиях остаётся
 * простое уведомление с тремя кнопками, как раньше — оба класса входят
 * в стандартный SDK, сторонних библиотек не требуется.
 *
 * Экран NowPlaying управляет плеером через bindService() и интерфейс PlayerBinder.
 */
public class PlayerService extends Service {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.github.skobsrpsk.holomusic.util.LocaleHelper.wrap(newBase));
    }


    public static final String ACTION_TOGGLE_PLAY = "com.github.skobsrpsk.holomusic.TOGGLE_PLAY";
    public static final String ACTION_NEXT = "com.github.skobsrpsk.holomusic.NEXT";
    public static final String ACTION_PREVIOUS = "com.github.skobsrpsk.holomusic.PREVIOUS";
    public static final String ACTION_STOP = "com.github.skobsrpsk.holomusic.STOP";
    public static final String ACTION_START_QUEUE = "com.github.skobsrpsk.holomusic.START_QUEUE";

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "holo_music_playback";
    private static final int ART_TARGET_SIZE = 400;

    // Очередь общая для всего приложения — простой статический стейт.
    private static final List<Song> queue = new ArrayList<>();
    private static int currentIndex = -1;
    private static boolean shuffle = false;
    private static int repeatMode = REPEAT_OFF;

    // Честный shuffle без повторов: "мешок" ещё не сыгранных индексов
    // (перемешивается заново только когда опустеет) + история фактически
    // сыгранных треков для корректной работы "предыдущий" внутри shuffle.
    // Раньше next() под shuffle делал Random().nextInt(queue.size()) на
    // каждый шаг — то есть каждый трек мог выпасть повторно в любой момент;
    // на небольшой библиотеке повторы вылезали почти сразу (см. парадокс
    // дней рождения). Мешок гарантирует, что все треки сыграют по разу,
    // прежде чем какой-либо начнёт повторяться.
    private static final List<Integer> shuffleBag = new ArrayList<>();
    private static final List<Integer> shuffleHistory = new ArrayList<>();
    private static int shuffleHistoryPos = -1;

    private MediaPlayer mediaPlayer;
    private volatile boolean playerPrepared = false;

    // ---------- Эквалайзер / бас / громкость ----------
    // Привязаны к общему audioSessionId, а не к конкретному MediaPlayer —
    // мы пересоздаём MediaPlayer на каждый трек (releasePlayer() + new
    // MediaPlayer()), но если все они используют один и тот же
    // audioSessionId, один раз созданные эффекты продолжают действовать на
    // любой новый MediaPlayer с этим ID без пересоздания самих эффектов.
    private int audioSessionId = 0;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private LoudnessEnhancer loudnessEnhancer;
    private final IBinder binder = new PlayerBinder();
    private PlaybackListener listener;
    private final Handler progressHandler = new Handler();

    private MediaSession mediaSession;
    private Bitmap currentArt;

    // ---------- Аудиофокус и наушники ----------
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // только API 26+
    private boolean resumeOnFocusGain = false;
    private boolean duckedVolume = false;
    private BroadcastReceiver noisyReceiver;
    private boolean noisyReceiverRegistered = false;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Фокус отобрали насовсем (другое приложение начало
                            // играть музыку) — ставим на паузу и не пытаемся
                            // возобновиться сами.
                            resumeOnFocusGain = false;
                            if (isPlaying()) togglePlayPause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Временная потеря (например, звонок) — пауза,
                            // но запоминаем, что надо возобновить после AUDIOFOCUS_GAIN.
                            resumeOnFocusGain = isPlaying();
                            if (isPlaying()) togglePlayPause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Короткий звук (уведомление другого приложения) —
                            // не пауза, а приглушение громкости.
                            if (mediaPlayer != null && playerPrepared) {
                                mediaPlayer.setVolume(0.2f, 0.2f);
                                duckedVolume = true;
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            if (duckedVolume && mediaPlayer != null && playerPrepared) {
                                mediaPlayer.setVolume(1f, 1f);
                                duckedVolume = false;
                            }
                            if (resumeOnFocusGain) {
                                resumeOnFocusGain = false;
                                if (!isPlaying()) togglePlayPause();
                            }
                            break;
                    }
                }
            };

    /** Запрашивает аудиофокус перед стартом воспроизведения. Без него по правилам Android играть не стоит. */
    private boolean requestAudioFocus() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return true; // сервис недоступен — не блокируем воспроизведение

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusChangeListener)
                        .build();
            }
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            result = audioManager.requestAudioFocus(focusChangeListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }

    private void registerNoisyReceiverIfNeeded() {
        if (noisyReceiverRegistered) return;
        if (noisyReceiver == null) {
            noisyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Наушники выдернули (или Bluetooth-гарнитура отключилась) —
                    // без этого музыка продолжила бы играть вслух на динамик.
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && isPlaying()) {
                        togglePlayPause();
                    }
                }
            };
        }
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyReceiverRegistered = true;
    }

    private void unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {
            }
            noisyReceiverRegistered = false;
        }
    }

    public class PlayerBinder extends Binder {
        PlayerService getService() {
            return PlayerService.this;
        }
    }

    /** Запуск новой очереди воспроизведения из любого экрана списка треков. */
    public static void playQueue(Context context, List<Song> songs, int startIndex) {
        queue.clear();
        queue.addAll(songs);
        currentIndex = startIndex;
        resetShuffleState();

        Intent intent = new Intent(context, PlayerService.class);
        intent.setAction(ACTION_START_QUEUE);
        context.startService(intent);
    }

    /** Сбрасывает мешок и историю shuffle — вызывается на новую очередь и при включении shuffle. */
    private static void resetShuffleState() {
        shuffleBag.clear();
        shuffleHistory.clear();
        if (currentIndex >= 0) {
            shuffleHistory.add(currentIndex);
        }
        shuffleHistoryPos = shuffleHistory.size() - 1;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setupMediaSession();
            reserveAudioSession();
        }
    }

    /**
     * На API 21+ можно зарезервировать audioSessionId ещё до старта
     * воспроизведения через AudioManager.generateAudioSessionId() и сразу
     * создать на нём Equalizer/BassBoost/LoudnessEnhancer. Раньше сессия
     * бралась только из player.getAudioSessionId() внутри attachAudioSession(),
     * то есть эффекты появлялись только после первого реально запущенного
     * трека — до этого экран настроек эквалайзера был вынужден показывать
     * полосы как "недоступные", хотя на самом деле они просто ещё не были
     * созданы. Теперь они готовы сразу, как только сервис создан (то есть
     * как только что-либо к нему привязалось), а первый реальный MediaPlayer
     * в attachAudioSession() просто подключается к уже готовой сессии через
     * setAudioSessionId() — ветка "audioSessionId == 0" там больше не
     * сработает. На API < 21 generateAudioSessionId() недоступен, поэтому
     * там поведение прежнее: сессия появляется только с первым проигранным треком.
     */
    private void reserveAudioSession() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return;
        try {
            audioSessionId = audioManager.generateAudioSessionId();
            initAudioEffects(audioSessionId);
        } catch (Exception ignored) {
            audioSessionId = 0;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START_QUEUE.equals(action)) {
                playCurrent();
            } else if (ACTION_TOGGLE_PLAY.equals(action)) {
                togglePlayPause();
            } else if (ACTION_NEXT.equals(action)) {
                next();
            } else if (ACTION_PREVIOUS.equals(action)) {
                previous();
            } else if (ACTION_STOP.equals(action)) {
                stop();
            }
        }
        return START_NOT_STICKY;
    }

    public void setListener(PlaybackListener l) {
        this.listener = l;
    }

    /**
     * Снимает слушателя, только если он всё ещё совпадает с l. Раньше
     * onStop() экрана вызывал setListener(null) безусловно — но при переходе
     * MainActivity -> NowPlayingActivity оба экрана на короткое время "живы"
     * одновременно (Android запускает новый экран раньше, чем останавливает
     * старый). Если NowPlayingActivity успевала зарегистрироваться первой,
     * а MainActivity.onStop() срабатывал позже — она затирала уже актуального
     * слушателя обратно в null. Действия (пауза/next/shuffle) при этом
     * реально выполнялись в сервисе, но колбэк никто не получал — экран
     * и мини-плеер переставали обновлять иконки/текст. Сравнение по ссылке
     * перед очисткой убирает эту гонку.
     */
    public void clearListener(PlaybackListener l) {
        if (this.listener == l) {
            this.listener = null;
        }
    }

    // ---------- Управление воспроизведением ----------

    private void playCurrent() {
        if (currentIndex < 0 || currentIndex >= queue.size()) return;
        final Song song = queue.get(currentIndex);

        if (!requestAudioFocus()) {
            // Фокус не дали (например, другое приложение играет эксклюзивно) —
            // по правилам Android в этом случае играть не начинаем.
            return;
        }
        registerNoisyReceiverIfNeeded();

        releasePlayer();
        final MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        playerPrepared = false;

        try {
            attachAudioSession(player);
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(song.path);

            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    // Пока файл готовился асинхронно, пользователь мог успеть
                    // нажать next/previous ещё раз — тогда releasePlayer()
                    // уже отпустил именно этот mp и создал новый. Сравниваем
                    // по ссылке с текущим активным плеером: если это уже не
                    // он, просто ничего не делаем (повторный release() не
                    // нужен и не безопасен — mp уже освобождён).
                    if (mp != mediaPlayer) return;
                    playerPrepared = true;
                    mp.start();
                    updateSessionPlaybackState(true);
                    if (listener != null) listener.onPlaybackStateChanged(true);
                    startForeground(NOTIFICATION_ID, buildNotification(song, true));
                }
            });

            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (mp != mediaPlayer) return true;
                    // Битый/неподдерживаемый файл — не зависаем молча,
                    // едем дальше по очереди. Намеренно не next(): под
                    // REPEAT_ONE он бы просто перезапустил этот же битый
                    // файл и тут же снова упал в onError.
                    skipDueToError();
                    return true;
                }
            });

            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (mp != mediaPlayer) return;
                    onTrackFinished();
                }
            });

            player.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentArt = null;
        // Уведомление и сессию обновляем сразу (название/артист видны
        // мгновенно), не дожидаясь фактического старта воспроизведения —
        // так отклик на нажатие ощущается мгновенным. Как только onPrepared
        // реально запустит плеер, состояние play/pause в уведомлении
        // перерисуется повторно с актуальным статусом.
        startForeground(NOTIFICATION_ID, buildNotification(song, true));
        updateSessionMetadata(song);
        updateSessionPlaybackState(true);
        loadArtForNotification(song);

        if (listener != null) listener.onTrackChanged();
        progressHandler.post(progressRunnable);
    }

    private void onTrackFinished() {
        if (repeatMode == REPEAT_ONE) {
            playCurrent();
            return;
        }
        next();
    }

    public void togglePlayPause() {
        if (mediaPlayer == null) {
            playCurrent();
            return;
        }
        if (!playerPrepared) {
            // Плеер ещё грузится асинхронно (узкое окно в несколько
            // десятков мс) — вызывать start()/pause()/isPlaying() сейчас
            // небезопасно, MediaPlayer бросит IllegalStateException.
            // onPrepared сам запустит воспроизведение, когда будет готов.
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        } else {
            if (!requestAudioFocus()) return; // не дали фокус — не возобновляем
            registerNoisyReceiverIfNeeded();
            mediaPlayer.start();
        }
        Song song = getCurrentSong();
        if (song != null) {
            startForeground(NOTIFICATION_ID, buildNotification(song, mediaPlayer.isPlaying()));
        }
        updateSessionPlaybackState(isPlaying());
        if (listener != null) listener.onPlaybackStateChanged(isPlaying());
    }

    public void next() {
        if (queue.isEmpty()) return;
        if (repeatMode == REPEAT_ONE) {
            // Повтор одного трека — Next не должен уводить по очереди
            // дальше, только перезапускать текущий трек. Раньше это
            // обеспечивалось только для естественного завершения трека
            // (onTrackFinished), а вручную нажатый Next в конце очереди
            // просто перезапускал последний трек — так же выглядело
            // "работает", но по совпадению с тем же багом, что чинили
            // для REPEAT_OFF (п.1 прошлого релиза). Когда тот баг убрали,
            // REPEAT_ONE с ним заодно перестал "перезапускать" и здесь
            // выглядел сломанным. Явно обрабатываем режим здесь — работает
            // одинаково из любой позиции в очереди, не только на границе.
            playCurrent();
            return;
        }
        advanceToNextInQueue();
    }

    /**
     * Отдельный путь для восстановления после ошибки воспроизведения —
     * всегда уходит на следующий трек в очереди, даже если включён
     * REPEAT_ONE. Если звать здесь next(), битый/неподдерживаемый файл
     * под REPEAT_ONE запускался бы через playCurrent() заново, тут же
     * снова падал в onError и уходил в бесконечный цикл повторных попыток.
     */
    private void skipDueToError() {
        advanceToNextInQueue();
    }

    private void advanceToNextInQueue() {
        if (queue.isEmpty()) return;
        if (shuffle) {
            if (shuffleHistoryPos < shuffleHistory.size() - 1) {
                // Мы до этого уже уходили "назад" по shuffle-истории —
                // просто идём вперёд по уже пройденному пути, не трогая мешок.
                shuffleHistoryPos++;
                currentIndex = shuffleHistory.get(shuffleHistoryPos);
            } else {
                if (shuffleBag.isEmpty()) {
                    if (repeatMode != REPEAT_ALL && shuffleHistory.size() >= queue.size()) {
                        // Все треки уже сыграли по разу и повтор всей очереди не запрошен — стоп на месте.
                        return;
                    }
                    refillShuffleBag();
                }
                int nextIndex = shuffleBag.remove(0);
                shuffleHistory.add(nextIndex);
                shuffleHistoryPos = shuffleHistory.size() - 1;
                currentIndex = nextIndex;
            }
        } else {
            int nextIndex = currentIndex + 1;
            if (nextIndex >= queue.size()) {
                if (repeatMode == REPEAT_ALL) {
                    currentIndex = 0;
                } else {
                    // Дошли до конца очереди без повтора — раньше тут всё
                    // равно вызывался playCurrent(), из-за чего последний
                    // трек перезапускался по кругу. Вместо этого просто
                    // останавливаемся на месте, как уже делает shuffle-ветка
                    // выше в этом же методе.
                    notifyStoppedAtQueueEnd();
                    return;
                }
            } else {
                currentIndex = nextIndex;
            }
        }
        playCurrent();
    }

    /**
     * Очередь без повтора дошла до конца — либо естественно (через
     * onCompletion, MediaPlayer уже сам остановился), либо вручную нажали
     * Next на последнем треке. В первом случае isPlaying() честно вернёт
     * false; во втором трек мог всё ещё физически играть — берём реальное
     * состояние плеера, а не считаем его остановленным заранее, иначе
     * уведомление/мини-плеер соврут о паузе поверх реально играющего трека.
     * currentIndex и очередь не трогаем — последний трек остаётся
     * выбранным и виден в мини-плеере.
     */
    private void notifyStoppedAtQueueEnd() {
        boolean playing = isPlaying();
        updateSessionPlaybackState(playing);
        Song song = getCurrentSong();
        if (song != null) {
            startForeground(NOTIFICATION_ID, buildNotification(song, playing));
        }
        if (listener != null) listener.onPlaybackStateChanged(playing);
    }

    public void previous() {
        if (queue.isEmpty()) return;
        if (repeatMode == REPEAT_ONE) {
            // Симметрично next() — Previous тоже просто перезапускает
            // текущий трек, а не уводит по очереди назад.
            playCurrent();
            return;
        }
        if (shuffle) {
            if (shuffleHistoryPos > 0) {
                shuffleHistoryPos--;
                currentIndex = shuffleHistory.get(shuffleHistoryPos);
                playCurrent();
            }
            // Некуда возвращаться назад по shuffle-истории — просто ничего не делаем.
            return;
        }
        currentIndex--;
        if (currentIndex < 0) {
            currentIndex = (repeatMode == REPEAT_ALL) ? queue.size() - 1 : 0;
        }
        playCurrent();
    }

    /** Перемешивает индексы ещё не сыгранных в этом "раунде" треков, избегая немедленного повтора текущего. */
    private void refillShuffleBag() {
        shuffleBag.clear();
        for (int i = 0; i < queue.size(); i++) {
            shuffleBag.add(i);
        }
        java.util.Collections.shuffle(shuffleBag);
        if (!shuffleBag.isEmpty() && shuffleBag.get(0) == currentIndex && shuffleBag.size() > 1) {
            java.util.Collections.swap(shuffleBag, 0, 1);
        }
    }

    /**
     * Полная остановка — не пауза. Останавливает плеер, убирает уведомление
     * и сбрасывает очередь. В отличие от паузы, после стопа мини-плеер
     * должен исчезнуть с экранов (это решает вызывающий UI по колбэку).
     */
    public void stop() {
        releasePlayer();
        playerPrepared = false;
        currentIndex = -1;
        queue.clear();
        currentArt = null;
        progressHandler.removeCallbacks(progressRunnable);
        abandonAudioFocus();
        unregisterNoisyReceiver();
        releaseAudioEffects();
        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 1.0f)
                    .build());
        }
        stopForeground(true);
        stopSelf();
        if (listener != null) {
            listener.onPlaybackStateChanged(false);
            listener.onTrackChanged();
        }
    }

    public void seekTo(int ms) {
        if (mediaPlayer != null && playerPrepared) mediaPlayer.seekTo(ms);
        updateSessionPlaybackState(isPlaying());
    }

    public boolean isPlaying() {
        return mediaPlayer != null && playerPrepared && mediaPlayer.isPlaying();
    }

    public int getCurrentPosition() {
        return (mediaPlayer != null && playerPrepared) ? mediaPlayer.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return (mediaPlayer != null && playerPrepared) ? mediaPlayer.getDuration() : 0;
    }

    public Song getCurrentSong() {
        if (currentIndex < 0 || currentIndex >= queue.size()) return null;
        return queue.get(currentIndex);
    }

    /**
     * Порядок реального воспроизведения — индексы в queue в том порядке,
     * в котором треки будут (или уже) сыграны: текущий трек первым, дальше
     * ещё не пройденный хвост shuffle-истории (если до этого жали "назад"),
     * затем остаток мешка. Без shuffle это просто 0..queue.size()-1.
     *
     * Раньше "Очередь воспроизведения" всегда показывала queue в порядке
     * добавления, даже когда включён shuffle — реальный порядок хранится
     * отдельно в shuffleBag/shuffleHistory и не совпадает с ним. Список
     * выглядел так, будто перемешивание не работает, хотя по факту играло
     * правильно, просто отображался не тот порядок.
     */
    public List<Integer> getQueueOrderIndices() {
        List<Integer> order = new ArrayList<>();
        if (!shuffle) {
            for (int i = 0; i < queue.size(); i++) order.add(i);
            return order;
        }
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            order.add(currentIndex);
        }
        for (int i = shuffleHistoryPos + 1; i < shuffleHistory.size(); i++) {
            order.add(shuffleHistory.get(i));
        }
        order.addAll(shuffleBag);
        return order;
    }

    /** Треки в реальном порядке воспроизведения — см. getQueueOrderIndices(). */
    public List<Song> getQueueSnapshot() {
        List<Song> result = new ArrayList<>();
        for (int idx : getQueueOrderIndices()) {
            if (idx >= 0 && idx < queue.size()) result.add(queue.get(idx));
        }
        return result;
    }

    public int getCurrentQueueIndex() {
        return currentIndex;
    }

    /**
     * Перейти на конкретный трек уже загруженной очереди. index — это
     * настоящий индекс в queue (НЕ позиция в списке getQueueSnapshot()!).
     * Экраны, показывающие getQueueSnapshot(), должны брать реальный индекс
     * из getQueueOrderIndices() по позиции в списке, а не передавать позицию
     * напрямую — иначе при включённом shuffle тап попадёт не в тот трек.
     */
    public void playAtIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        currentIndex = index;
        if (shuffle) {
            // Синхронизируем shuffle-историю с ручным переходом, иначе
            // previous() внутри shuffle поведёт себя странно после такого прыжка.
            shuffleHistory.add(index);
            shuffleHistoryPos = shuffleHistory.size() - 1;
            shuffleBag.remove(Integer.valueOf(index));
        }
        playCurrent();
    }

    /**
     * Вставить трек сразу после текущего — "играть следующим".
     * Остальная очередь после него сохраняется без изменений.
     *
     * ВАЖНО: вставка сдвигает индексы всех треков после insertAt на +1, а
     * shuffleBag/shuffleHistory хранят именно индексы — после вставки они
     * были бы битые. Проще и безопаснее пересобрать shuffle-состояние с нуля,
     * чем пытаться пересчитать сдвиг во всех местах, где эти индексы лежат.
     */
    public void playNext(Song song) {
        if (queue.isEmpty()) {
            queue.add(song);
            currentIndex = 0;
            resetShuffleState();
            playCurrent();
            return;
        }
        int insertAt = currentIndex + 1;
        queue.add(insertAt, song);
        if (shuffle) resetShuffleState();
    }

    /** Добавить трек в конец очереди, не прерывая текущее воспроизведение. */
    public void addToQueueEnd(Song song) {
        if (queue.isEmpty()) {
            queue.add(song);
            currentIndex = 0;
            resetShuffleState();
            playCurrent();
            return;
        }
        queue.add(song);
        // Добавление в конец не сдвигает существующие индексы, но новый
        // трек всё равно должен попасть в мешок, если shuffle включён.
        if (shuffle && !shuffleBag.isEmpty()) {
            shuffleBag.add(queue.size() - 1);
        }
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        if (shuffle) {
            resetShuffleState();
        }
    }

    /** В отличие от toggleShuffle(), явно включает/выключает — нужно кнопке "Перемешать" над списком. */
    public void setShuffle(boolean enabled) {
        if (shuffle == enabled) return;
        shuffle = enabled;
        if (shuffle) {
            resetShuffleState();
        }
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void cycleRepeatMode() {
        repeatMode = (repeatMode + 1) % 3;
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public void toggleFavoriteCurrent() {
        Song song = getCurrentSong();
        if (song != null) {
            SortPrefs.toggleFavorite(this, song.id);
        }
    }

    // ---------- Прогресс ----------

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && listener != null) {
                listener.onProgress(getCurrentPosition(), getDuration());
            }
            progressHandler.postDelayed(this, 500);
        }
    };

    // ---------- MediaSession (API 21+) ----------

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "HoloMusicSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (!isPlaying()) togglePlayPause();
            }

            @Override
            public void onPause() {
                if (isPlaying()) togglePlayPause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
    }

    /**
     * Точечно обновляет title/artist/album уже загруженного в очередь трека
     * после правки его тегов "на лету" — сам Song в очереди был прочитан ДО
     * правки и всё ещё хранит старые значения, а полную перезагрузку
     * очереди ради одного трека делать незачем. Если это ещё и текущий
     * трек — обновляем заодно уведомление/MediaSession и зовём listener,
     * чтобы экран "Сейчас играет" тоже перерисовался.
     */
    public void refreshSongMetadata(long songId, String title, String artist, String album) {
        boolean isCurrent = false;
        for (Song s : queue) {
            if (s.id == songId) {
                s.title = title;
                s.artist = artist;
                s.album = album;
            }
        }
        if (currentIndex >= 0 && currentIndex < queue.size() && queue.get(currentIndex).id == songId) {
            isCurrent = true;
        }
        if (isCurrent) {
            Song current = getCurrentSong();
            if (current != null) {
                updateSessionMetadata(current);
                startForeground(NOTIFICATION_ID, buildNotification(current, isPlaying()));
            }
            if (listener != null) listener.onTrackChanged();
        }
    }

    private void updateSessionMetadata(Song song) {
        if (mediaSession == null) return;
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, song.duration);
        if (currentArt != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArt);
        }
        mediaSession.setMetadata(builder.build());
    }

    private void updateSessionPlaybackState(boolean playing) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO;
        PlaybackState state = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        getCurrentPosition(), 1.0f)
                .build();
        mediaSession.setPlaybackState(state);
    }

    /** Грузим обложку в фоне и, если это ещё актуальный трек, обновляем уведомление и сессию. */
    private void loadArtForNotification(final Song song) {
        if (!SortPrefs.isShowArtOnLockscreen(this)) {
            // Настройка выключена — не тратим время на декодирование и не
            // передаём bitmap ни в уведомление, ни в MediaSession (именно
            // оттуда обложка попадает на экран блокировки).
            return;
        }
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                return AlbumArtLoader.decodeEmbeddedArt(song.path, ART_TARGET_SIZE);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                Song stillCurrent = getCurrentSong();
                if (bitmap == null || stillCurrent == null || stillCurrent.id != song.id) return;
                currentArt = bitmap;
                updateSessionMetadata(song);
                startForeground(NOTIFICATION_ID, buildNotification(song, isPlaying()));
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // ---------- Уведомление ----------

    private Notification buildNotification(Song song, boolean playing) {
        createChannelIfNeeded();

        Intent contentIntent = new Intent(this, NowPlayingActivity.class);
        PendingIntent contentPending = PendingIntent.getActivity(this, 0, contentIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent playPending = actionPendingIntent(ACTION_TOGGLE_PLAY, 1);
        PendingIntent nextPending = actionPendingIntent(ACTION_NEXT, 2);
        PendingIntent prevPending = actionPendingIntent(ACTION_PREVIOUS, 3);
        PendingIntent stopPending = actionPendingIntent(ACTION_STOP, 4);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(song.title)
                .setContentText(song.artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentPending)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPending)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "Play/Pause", playPending)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending);

        if (currentArt != null) {
            builder.setLargeIcon(currentArt);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            // MediaStyle: система сама рисует обложку крупно, компактные
            // кнопки и — на Android 13+ — прогресс-бар по данным сессии.
            // В компактном виде оставляем только prev/play-pause/next (индексы
            // 0,1,2) — Stop добавлен четвёртым действием и виден в развёрнутом
            // уведомлении, чтобы не перегружать компактный вид шторки.
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }

        return builder.build();
    }

    private PendingIntent actionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlayerService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        // PendingIntent.getService — напрямую в сервис, без промежуточного
        // BroadcastReceiver. Раньше нажатие в уведомлении шло через
        // NotificationActionReceiver.onReceive() -> startService(), что
        // добавляло лишний хоп и попадало под системный троттлинг доставки
        // broadcast'ов — отсюда была заметная задержка перед реакцией.
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    // ---------- Эквалайзер / бас / громкость ----------

    /**
     * Привязывает MediaPlayer к общему audioSessionId. На API 21+ сессия
     * (и эффекты на ней) уже зарезервирована заранее в reserveAudioSession(),
     * так что сюда всегда попадаем в ветку "else" — просто переключаем новый
     * MediaPlayer на готовый ID. Ветка "audioSessionId == 0" остаётся только
     * для API < 21 (или если reserveAudioSession() по какой-то причине не
     * сработала) — тогда, как и раньше, берём дефолтный ID первого реального
     * MediaPlayer и на нём один раз создаём эффекты. В обоих случаях все
     * следующие MediaPlayer (на каждый новый трек) просто переключаются
     * на тот же ID — эффекты пересоздавать не нужно, они продолжают
     * действовать на любой MediaPlayer с этим audioSessionId.
     */
    private void attachAudioSession(MediaPlayer player) {
        if (audioSessionId == 0) {
            audioSessionId = player.getAudioSessionId();
            initAudioEffects(audioSessionId);
        } else {
            try {
                player.setAudioSessionId(audioSessionId);
            } catch (Exception ignored) {
            }
        }
    }

    private void initAudioEffects(int sessionId) {
        boolean enabled = EqualizerPrefs.isEnabled(this);

        try {
            equalizer = new Equalizer(0, sessionId);
            equalizer.setEnabled(enabled);
            applyBandsWithPreamp();
        } catch (Exception ignored) {
            equalizer = null;
        }

        try {
            bassBoost = new BassBoost(0, sessionId);
            bassBoost.setEnabled(enabled);
            bassBoost.setStrength((short) EqualizerPrefs.getBassBoostStrength(this));
        } catch (Exception ignored) {
            bassBoost = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                loudnessEnhancer = new LoudnessEnhancer(sessionId);
                loudnessEnhancer.setEnabled(enabled);
                loudnessEnhancer.setTargetGain(EqualizerPrefs.getLoudnessGainMb(this));
            } catch (Exception ignored) {
                loudnessEnhancer = null;
            }
        }
    }

    /** Применяет сохранённые уровни полос + преамп (преамп — общий сдвиг всей кривой, а не отдельная полоса). */
    private void applyBandsWithPreamp() {
        if (equalizer == null) return;
        try {
            short bands = equalizer.getNumberOfBands();
            int[] stored = EqualizerPrefs.getBandLevels(this, bands);
            int preamp = EqualizerPrefs.getPreamp(this);
            short[] range = equalizer.getBandLevelRange();
            for (short i = 0; i < bands; i++) {
                int level = stored[i] + preamp;
                level = Math.max(range[0], Math.min(range[1], level));
                equalizer.setBandLevel(i, (short) level);
            }
        } catch (Exception ignored) {
        }
    }

    public boolean isEqualizerAvailable() {
        return equalizer != null;
    }

    public int getNumberOfBands() {
        try {
            return equalizer != null ? equalizer.getNumberOfBands() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public short[] getBandLevelRange() {
        try {
            return equalizer != null ? equalizer.getBandLevelRange() : new short[]{-1500, 1500};
        } catch (Exception e) {
            return new short[]{-1500, 1500};
        }
    }

    public int getBandFrequencyHz(int band) {
        try {
            return equalizer != null ? equalizer.getCenterFreq((short) band) / 1000 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public String[] getEqualizerPresetNames() {
        try {
            if (equalizer == null) return new String[0];
            short count = equalizer.getNumberOfPresets();
            String[] names = new String[count];
            for (short i = 0; i < count; i++) {
                names[i] = equalizer.getPresetName(i);
            }
            return names;
        } catch (Exception e) {
            return new String[0];
        }
    }

    public void setEqualizerEnabled(boolean enabled) {
        EqualizerPrefs.setEnabled(this, enabled);
        if (equalizer != null) {
            try {
                equalizer.setEnabled(enabled);
            } catch (Exception ignored) {
            }
        }
        if (bassBoost != null) {
            try {
                bassBoost.setEnabled(enabled);
            } catch (Exception ignored) {
            }
        }
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setEnabled(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    /** Ручная правка полосы — сразу переключает пресет в "пользовательский" (-1), как в любом обычном эквалайзере. */
    public void setBandLevel(int band, int level) {
        int bands = getNumberOfBands();
        EqualizerPrefs.setBandLevel(this, band, level, Math.max(bands, band + 1));
        EqualizerPrefs.setPresetIndex(this, -1);
        applyBandsWithPreamp();
    }

    public void applyPreset(int presetIndex) {
        EqualizerPrefs.setPresetIndex(this, presetIndex);
        if (equalizer == null) return;
        try {
            equalizer.usePreset((short) presetIndex);
            short bands = equalizer.getNumberOfBands();
            int[] levels = new int[bands];
            for (short i = 0; i < bands; i++) {
                levels[i] = equalizer.getBandLevel(i);
            }
            // Сохраняем реальные уровни после применения пресета — если
            // потом пользователь чуть подвинет полосу вручную, преамп
            // должен считаться от них, а не от нуля.
            EqualizerPrefs.setBandLevels(this, levels);
        } catch (Exception ignored) {
        }
    }

    public void setPreamp(int mB) {
        EqualizerPrefs.setPreamp(this, mB);
        applyBandsWithPreamp();
    }

    public void setBassBoostStrength(int strength) {
        EqualizerPrefs.setBassBoostStrength(this, strength);
        if (bassBoost != null) {
            try {
                bassBoost.setStrength((short) strength);
            } catch (Exception ignored) {
            }
        }
    }

    public void setLoudnessGain(int mB) {
        EqualizerPrefs.setLoudnessGainMb(this, mB);
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setTargetGain(mB);
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseAudioEffects() {
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {
            }
            equalizer = null;
        }
        if (bassBoost != null) {
            try {
                bassBoost.release();
            } catch (Exception ignored) {
            }
            bassBoost = null;
        }
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.release();
            } catch (Exception ignored) {
            }
            loudnessEnhancer = null;
        }
        audioSessionId = 0;
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    @Override
    public void onDestroy() {
        progressHandler.removeCallbacks(progressRunnable);
        releasePlayer();
        abandonAudioFocus();
        unregisterNoisyReceiver();
        releaseAudioEffects();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }
}
