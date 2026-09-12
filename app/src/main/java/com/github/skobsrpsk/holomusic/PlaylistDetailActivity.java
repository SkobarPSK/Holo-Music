package com.github.skobsrpsk.holomusic;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.adapter.SongAdapter;
import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.List;

public class PlaylistDetailActivity extends BaseActivity {

    public static final String EXTRA_NAME = "playlist_name";
    private static final int MENU_ADD_TRACKS = 1;

    private String playlistName;
    private final List<Song> songs = new ArrayList<>();
    private SongAdapter adapter;
    private TextView emptyText;
    private boolean justCreated = false;
    private boolean pendingReload = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setBaseContentView(R.layout.activity_list_with_header);

        playlistName = getIntent().getStringExtra(EXTRA_NAME);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(playlistName);
        }

        TextView listTitle = findViewById(R.id.text_list_title);
        TextView listSubtitle = findViewById(R.id.text_list_subtitle);
        listTitle.setText(playlistName);
        listSubtitle.setVisibility(View.GONE);

        ListView listView = findViewById(R.id.list_view);
        emptyText = findViewById(R.id.empty_text);

        adapter = new SongAdapter(this, songs);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PlayerService.playQueue(PlaylistDetailActivity.this, songs, position);
                NowPlayingActivity.start(PlaylistDetailActivity.this);
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                final Song song = songs.get(position);
                final String[] items = {
                        getString(R.string.remove_from_playlist),
                        getString(R.string.play_next),
                        getString(R.string.add_to_queue),
                        getString(R.string.add_to_playlist),
                        getString(R.string.go_to_artist),
                        getString(R.string.song_info),
                        getString(R.string.set_as_ringtone),
                        getString(R.string.delete_song),
                };
                new AlertDialog.Builder(PlaylistDetailActivity.this)
                        .setTitle(song.title)
                        .setItems(items, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                switch (which) {
                                    case 0:
                                        PlaylistStore.removeSongFromPlaylist(PlaylistDetailActivity.this, playlistName, song.id);
                                        reload();
                                        break;
                                    case 1:
                                        SongActions.playNext(PlaylistDetailActivity.this, song);
                                        break;
                                    case 2:
                                        SongActions.addToQueue(PlaylistDetailActivity.this, song);
                                        break;
                                    case 3:
                                        SongActions.addToPlaylist(PlaylistDetailActivity.this, song);
                                        break;
                                    case 4:
                                        SongActions.goToArtist(PlaylistDetailActivity.this, song);
                                        break;
                                    case 5:
                                        SongActions.showInfo(PlaylistDetailActivity.this, song);
                                        break;
                                    case 6:
                                        SongActions.setAsRingtone(PlaylistDetailActivity.this, song);
                                        break;
                                    case 7:
                                        SongActions.confirmDelete(PlaylistDetailActivity.this, song, new SongActions.Callback() {
                                            @Override
                                            public void onSongDeleted() {
                                                reload();
                                            }
                                        });
                                        break;
                                }
                            }
                        })
                        .show();
                return true;
            }
        });

        findViewById(R.id.btn_play_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (songs.isEmpty()) return;
                PlayerService.playQueue(PlaylistDetailActivity.this, songs, 0);
            }
        });
        findViewById(R.id.btn_shuffle_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (songs.isEmpty()) return;
                PlayerService.playQueue(PlaylistDetailActivity.this, songs, 0);
                if (serviceBound) playerService.setShuffle(true);
            }
        });
        findViewById(R.id.btn_add_all_to_queue).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serviceBound) return;
                for (Song s : songs) {
                    playerService.addToQueueEnd(s);
                }
            }
        });

        justCreated = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (justCreated) {
            justCreated = false;
            reload();
            return;
        }
        if (pendingReload) {
            // Только что вернулись из подборщика треков — состав плейлиста
            // мог измениться. При обычном возврате из другого приложения
            // (без похода в подборщик) список не трогаем, чтобы не мигать
            // повторной загрузкой на пустом месте.
            pendingReload = false;
            reload();
        }
    }

    @Override
    protected void onNowPlayingChanged(long currentSongId) {
        if (adapter != null) adapter.setCurrentlyPlayingId(currentSongId);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_ADD_TRACKS, Menu.NONE, R.string.add_to_playlist)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ADD_TRACKS) {
            pendingReload = true;
            Intent intent = new Intent(this, PlaylistTrackPickerActivity.class);
            intent.putExtra(PlaylistTrackPickerActivity.EXTRA_PLAYLIST_NAME, playlistName);
            startActivity(intent);
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        new AsyncTask<Void, Void, List<Song>>() {
            @Override
            protected List<Song> doInBackground(Void... voids) {
                List<Song> result = new ArrayList<>();
                for (Long songId : PlaylistStore.getPlaylist(PlaylistDetailActivity.this, playlistName).songIds) {
                    Song song = MediaScanner.getSongById(PlaylistDetailActivity.this, songId);
                    if (song != null) {
                        song.isBroken = MediaScanner.isLikelyBroken(song.path);
                        result.add(song);
                    }
                }
                return result;
            }

            @Override
            protected void onPostExecute(List<Song> result) {
                songs.clear();
                songs.addAll(result);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
                if (serviceBound) {
                    Song current = playerService.getCurrentSong();
                    adapter.setCurrentlyPlayingId(current != null ? current.id : -1);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
