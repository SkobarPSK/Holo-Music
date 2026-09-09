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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.model.Song;

/**
 * Общий предок экранов, на которых должна быть видна мини-плашка плеера
 * снизу (все, кроме NowPlayingActivity и SettingsActivity). Наследник
 * вызывает setBaseContentView(int layoutRes) вместо обычного setContentView —
 * реальный layout встраивается в контейнер, под которым остаётся место
 * для мини-плеера.
 */
public abstract class BaseActivity extends Activity implements PlaybackListener {

    private FrameLayout contentContainer;
    private View miniPlayerRoot;
    private TextView miniTitle, miniArtist;
    private ImageButton miniPlayPause, miniPrevious, miniNext, miniStop;

    // Мини-плеер должен прятаться, пока открыто боковое меню (иначе он
    // остаётся поверх затемнённого контента и торчит поверх панели меню).
    // hasCurrentSong — есть ли вообще что показывать; hiddenByDrawer —
    // временно скрыт извне (MainActivity дёргает при открытии/закрытии
    // DrawerContainer). Реальная видимость — пересечение обоих условий.
    private boolean hasCurrentSong = false;
    private boolean hiddenByDrawer = false;

    protected PlayerService playerService;
    protected boolean serviceBound = false;
    private boolean activityStarted = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerService = ((PlayerService.PlayerBinder) service).getService();
            serviceBound = true;
            if (activityStarted) {
                // Коннект — асинхронный колбэк; редко, но возможно, что он
                // прилетает уже после onStop() (например, если пользователь
                // успел быстро уйти с экрана). Регистрировать слушателя
                // в этом случае не нужно — иначе он "зависнет" в сервисе
                // и его придётся ждать следующего onStop() для очистки.
                playerService.setListener(BaseActivity.this);
                refreshMiniPlayer();
                // Пока экран был не виден (например, ушли на NowPlayingActivity
                // и там переключили пару треков), он не был подписан на
                // события — live onTrackChanged() эти смены просто пропустил.
                // Раньше метка играющего трека в списке оставалась старой до
                // следующей живой смены трека. Тут при каждом переподключении
                // принудительно синхронизируем метку с реальным состоянием
                // сервиса, а не полагаемся только на то, что событие успеет
                // прилететь, пока экран активен.
                Song current = playerService.getCurrentSong();
                onNowPlayingChanged(current != null ? current.id : -1);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    /**
     * Наследники вызывают это вместо setContentView(). Раньше мини-плеер
     * лежал поверх контента (FrameLayout + gravity=bottom) — на экранах,
     * где список треков почти ровно заполнял высоту экрана, последняя
     * строка оказывалась под плашкой и была недоступна для тапа. Теперь
     * контент и мини-плеер — соседи в вертикальном LinearLayout: контент
     * сжимается (layout_weight=1), плеер занимает своё место снизу,
     * ничего не перекрывая.
     */
    protected void setBaseContentView(int layoutRes) {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        contentContainer = new FrameLayout(this);
        android.widget.LinearLayout.LayoutParams contentParams = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(contentContainer, contentParams);

        LayoutInflater.from(this).inflate(layoutRes, contentContainer, true);

        miniPlayerRoot = LayoutInflater.from(this).inflate(R.layout.view_mini_player, root, false);
        android.widget.LinearLayout.LayoutParams miniParams = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(miniPlayerRoot, miniParams);

        setContentView(root);
        bindMiniPlayerViews();
    }

    private void bindMiniPlayerViews() {
        miniTitle = miniPlayerRoot.findViewById(R.id.mini_title);
        miniArtist = miniPlayerRoot.findViewById(R.id.mini_artist);
        miniPlayPause = miniPlayerRoot.findViewById(R.id.mini_btn_play_pause);
        miniPrevious = miniPlayerRoot.findViewById(R.id.mini_btn_previous);
        miniNext = miniPlayerRoot.findViewById(R.id.mini_btn_next);
        miniStop = miniPlayerRoot.findViewById(R.id.mini_btn_stop);

        miniPlayerRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NowPlayingActivity.start(BaseActivity.this);
            }
        });
        miniPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceBound) playerService.togglePlayPause();
            }
        });
        miniPrevious.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceBound) playerService.previous();
            }
        });
        miniNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceBound) playerService.next();
            }
        });
        miniStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceBound) playerService.stop();
            }
        });
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

    private void refreshMiniPlayer() {
        if (miniPlayerRoot == null || !serviceBound) return;
        Song song = playerService.getCurrentSong();
        hasCurrentSong = song != null;
        applyMiniPlayerVisibility();
        if (song == null) return;
        miniTitle.setText(song.title);
        miniArtist.setText(song.artist);
        miniPlayPause.setImageResource(playerService.isPlaying()
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
    }

    /** Вызывается извне (MainActivity) при открытии/закрытии бокового меню. */
    protected void setMiniPlayerHiddenByDrawer(boolean hidden) {
        hiddenByDrawer = hidden;
        applyMiniPlayerVisibility();
    }

    private void applyMiniPlayerVisibility() {
        if (miniPlayerRoot == null) return;
        miniPlayerRoot.setVisibility(hasCurrentSong && !hiddenByDrawer ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onTrackChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshMiniPlayer();
                Song current = serviceBound ? playerService.getCurrentSong() : null;
                onNowPlayingChanged(current != null ? current.id : -1);
            }
        });
    }

    /**
     * Хук для наследников — вызывается на смену трека (после обновления
     * мини-плеера), чтобы подсветить текущий трек в своих списках.
     * По умолчанию ничего не делает.
     */
    protected void onNowPlayingChanged(long currentSongId) {
    }

    @Override
    public void onPlaybackStateChanged(final boolean isPlaying) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (miniPlayPause != null) {
                    miniPlayPause.setImageResource(isPlaying
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play);
                }
                if (miniPlayerRoot != null && !isPlaying && serviceBound && playerService.getCurrentSong() == null) {
                    hasCurrentSong = false;
                    applyMiniPlayerVisibility();
                }
            }
        });
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        // Мини-плеер прогресс не показывает — только полноэкранный плеер.
    }
}
