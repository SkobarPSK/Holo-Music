package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Outline;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.adapter.SongAdapter;
import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.AlbumArtLoader;

import java.util.List;
import java.util.Locale;

public class NowPlayingActivity extends Activity implements PlaybackListener {

    public static void start(Context context) {
        Intent intent = new Intent(context, NowPlayingActivity.class);
        context.startActivity(intent);
    }

    private TextView textTitle, textArtistAlbum, textPosition, textDuration;
    private ImageView textNoArt;
    private ImageButton btnPlayPause, btnPrevious, btnNext, btnShuffle, btnRepeat;
    private ImageButton btnQueue, btnSongMenu;
    private ImageView imageCover;
    private FrameLayout coverContainer;
    private SeekBar seekProgress;

    private PlayerService playerService;
    private boolean bound = false;
    private boolean activityStarted = false;
    private boolean userSeeking = false;

    // Пока диалог очереди открыт, держим ссылки на его adapter/список
    // индексов, чтобы обновлять его при смене трека извне (например, кнопкой
    // в уведомлении) — раньше это был статический снимок, который не
    // обновлялся, пока диалог открыт.
    private SongAdapter queueDialogAdapter;
    private List<Integer> queueDialogOrderIndices;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerService = ((PlayerService.PlayerBinder) service).getService();
            bound = true;
            if (activityStarted) {
                playerService.setListener(NowPlayingActivity.this);
                refreshUi();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.now_playing);
        }

        textTitle = findViewById(R.id.text_track_title);
        textArtistAlbum = findViewById(R.id.text_track_artist_album);
        textPosition = findViewById(R.id.text_position);
        textDuration = findViewById(R.id.text_duration);
        textNoArt = findViewById(R.id.text_no_art);
        imageCover = findViewById(R.id.image_cover);
        coverContainer = findViewById(R.id.cover_container);
        seekProgress = findViewById(R.id.seek_progress);

        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnPrevious = findViewById(R.id.btn_previous);
        btnNext = findViewById(R.id.btn_next);
        btnShuffle = findViewById(R.id.btn_shuffle);
        btnRepeat = findViewById(R.id.btn_repeat);
        btnQueue = findViewById(R.id.btn_queue);
        btnSongMenu = findViewById(R.id.btn_song_menu);

        makeCoverSquare();
        setupRoundedCover();

        btnQueue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQueueDialog();
            }
        });
        btnSongMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!bound) return;
                Song song = playerService.getCurrentSong();
                if (song == null) return;
                SongActions.showMenu(NowPlayingActivity.this, song, new SongActions.Callback() {
                    @Override
                    public void onSongDeleted() {
                        // Сам плеер уже умеет само-восстанавливаться при ошибке
                        // воспроизведения (OnErrorListener -> next()), так что
                        // тут просто закрываем экран — специально дёргать
                        // next() отсюда не нужно и рискует гонкой при очереди
                        // из одного трека.
                        finish();
                    }

                    @Override
                    public void onSongUpdated() {
                        // В отличие от удаления, трек никуда не делся — экран
                        // закрывать не нужно. Но playerService.getCurrentSong()
                        // всё ещё держит Song, прочитанный ДО правки тегов —
                        // сначала подтягиваем свежие title/artist/album из
                        // MediaStore (он уже актуален — SongActions успел его
                        // пересканировать) и патчим ими живую очередь, и только
                        // потом перерисовываем экран.
                        if (bound) {
                            Song refreshed = com.github.skobsrpsk.holomusic.util.MediaScanner.getSongById(
                                    NowPlayingActivity.this, song.id);
                            if (refreshed != null) {
                                playerService.refreshSongMetadata(
                                        refreshed.id, refreshed.title, refreshed.artist, refreshed.album);
                            }
                        }
                        refreshUi();
                    }
                });
            }
        });

        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) playerService.togglePlayPause();
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) playerService.next();
            }
        });
        btnPrevious.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) playerService.previous();
            }
        });
        btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) {
                    playerService.toggleShuffle();
                    updateShuffleRepeatUi();
                }
            }
        });
        btnRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) {
                    playerService.cycleRepeatMode();
                    updateShuffleRepeatUi();
                }
            }
        });

        seekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) textPosition.setText(formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (bound) playerService.seekTo(seekBar.getProgress());
            }
        });
    }

    /** Кнопка слева от названия — показывает текущую очередь в диалоге, тап переключает трек. */
    private void showQueueDialog() {
        if (!bound) return;

        queueDialogOrderIndices = playerService.getQueueOrderIndices();
        List<Song> queueSongs = playerService.getQueueSnapshot();
        Song current = playerService.getCurrentSong();

        ListView listView = new ListView(this);
        queueDialogAdapter = new SongAdapter(this, queueSongs);
        if (current != null) queueDialogAdapter.setCurrentlyPlayingId(current.id);
        listView.setAdapter(queueDialogAdapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.section_queue)
                .setView(listView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        // Пока диалог открыт, следующая смена трека (в том числе из
        // уведомления) должна обновить и его — см. onTrackChanged().
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                queueDialogAdapter = null;
                queueDialogOrderIndices = null;
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (bound && queueDialogOrderIndices != null && position < queueDialogOrderIndices.size()) {
                    // position — место в отображаемом списке, playAtIndex() же
                    // ждёт настоящий индекс в очереди сервиса.
                    playerService.playAtIndex(queueDialogOrderIndices.get(position));
                }
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    /** Обновляет открытый диалог очереди (если он сейчас открыт) на смену трека. */
    private void refreshQueueDialogIfOpen() {
        if (queueDialogAdapter == null || !bound) return;
        queueDialogOrderIndices = playerService.getQueueOrderIndices();
        List<Song> freshQueue = playerService.getQueueSnapshot();
        queueDialogAdapter.clear();
        queueDialogAdapter.addAll(freshQueue);
        Song current = playerService.getCurrentSong();
        queueDialogAdapter.setCurrentlyPlayingId(current != null ? current.id : -1);
    }

    /**
     * Лёгкое скругление углов обложки через ViewOutlineProvider — штатный
     * способ на API 21+, не требует ручной обрезки битмапа. На API 16-20
     * (совсем старые устройства) просто останутся прямые углы — не критично.
     */
    private void setupRoundedCover() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final float radiusPx = 16 * getResources().getDisplayMetrics().density;
            coverContainer.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            coverContainer.setClipToOutline(true);
        }
    }

    /**
     * Делаем область обложки квадратной программно (без ConstraintLayout):
     * высота выставляется равной фактической ширине после первого layout-прохода.
     */
    /**
     * Делаем область обложки квадратной программно (без ConstraintLayout).
     * В портретной ориентации ширина — это то, что задано layout'ом, а
     * высота свободна, поэтому подгоняем высоту под ширину (как раньше).
     * В ландшафте наоборот: cover_container в альбомном layout уже получает
     * фиксированную высоту (match_parent по вертикали экрана), а ширина
     * wrap_content — там дефицитный размер именно ширина, подгоняем её под
     * высоту. Без этой развилки в ландшафте обложка считалась бы от очень
     * широкого экрана и получалась гигантской по высоте, выталкивая кнопки
     * управления за пределы видимой области.
     */
    private void makeCoverSquare() {
        final boolean isLandscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        coverContainer.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewGroup.LayoutParams params = coverContainer.getLayoutParams();
                if (isLandscape) {
                    int height = coverContainer.getHeight();
                    if (height > 0 && params.width != height) {
                        params.width = height;
                        coverContainer.setLayoutParams(params);
                    }
                } else {
                    int width = coverContainer.getWidth();
                    if (width > 0 && params.height != width) {
                        params.height = width;
                        coverContainer.setLayoutParams(params);
                    }
                }
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
        if (bound) {
            playerService.clearListener(this);
            unbindService(connection);
            bound = false;
        }
    }

    private void refreshUi() {
        Song song = playerService.getCurrentSong();
        if (song == null) return;
        textTitle.setText(song.title);
        textArtistAlbum.setText(song.artist + " — " + song.album);
        seekProgress.setMax(playerService.getDuration());
        updateShuffleRepeatUi();
        onPlaybackStateChanged(playerService.isPlaying());
        loadCoverArt(song);
    }

    private void loadCoverArt(Song song) {
        textNoArt.setVisibility(View.VISIBLE);
        int targetSize = coverContainer.getWidth() > 0 ? coverContainer.getWidth() : 600;
        AlbumArtLoader.loadInto(song.id, song.path, imageCover, targetSize);
        // Прячем плейсхолдер-ноту, как только картинка реально подгрузится —
        // простая отложенная проверка через drawable, без отдельного callback.
        imageCover.postDelayed(new Runnable() {
            @Override
            public void run() {
                textNoArt.setVisibility(imageCover.getDrawable() == null ? View.VISIBLE : View.GONE);
            }
        }, 300);
    }

    private void updateShuffleRepeatUi() {
        int activeColor = getResources().getColor(R.color.holo_blue);
        int inactiveColor = getResources().getColor(R.color.text_secondary);

        btnShuffle.setColorFilter(playerService.isShuffle() ? activeColor : inactiveColor);

        int repeat = playerService.getRepeatMode();
        btnRepeat.setColorFilter(repeat == PlayerService.REPEAT_OFF ? inactiveColor : activeColor);
        btnRepeat.setImageResource(repeat == PlayerService.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
    }

    @Override
    public void onTrackChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshUi();
                refreshQueueDialogIfOpen();
            }
        });
    }

    @Override
    public void onPlaybackStateChanged(final boolean isPlaying) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btnPlayPause.setImageResource(isPlaying
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
            }
        });
    }

    @Override
    public void onProgress(final int positionMs, final int durationMs) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!userSeeking) {
                    seekProgress.setMax(durationMs);
                    seekProgress.setProgress(positionMs);
                    textPosition.setText(formatTime(positionMs));
                    textDuration.setText(formatTime(durationMs));
                }
            }
        });
    }

    private String formatTime(int ms) {
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
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
