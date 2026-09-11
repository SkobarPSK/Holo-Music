package com.github.skobsrpsk.holomusic;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.adapter.SongAdapter;
import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран списка треков конкретного альбома или артиста.
 * Шапка: заголовок + "Играть все / Перемешать / В очередь".
 * Тап по треку сразу начинает воспроизведение и открывает плеер.
 */
public class TrackListActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ALBUM_ID = "album_id";
    public static final String EXTRA_ARTIST_ID = "artist_id";
    public static final String EXTRA_ARTIST_NAME = "artist_name";
    public static final String EXTRA_TITLE = "title";

    public static final int MODE_ALBUM = 0;
    public static final int MODE_ARTIST = 1;

    private final List<Song> songs = new ArrayList<>();
    private SongAdapter adapter;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setBaseContentView(R.layout.activity_list_with_header);

        String title = getIntent().getStringExtra(EXTRA_TITLE);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            if (title != null) getActionBar().setTitle(title);
        }

        TextView listTitle = findViewById(R.id.text_list_title);
        TextView listSubtitle = findViewById(R.id.text_list_subtitle);
        listTitle.setText(title);
        listSubtitle.setVisibility(View.GONE);

        ListView listView = findViewById(R.id.list_view);
        emptyText = findViewById(R.id.empty_text);

        adapter = new SongAdapter(this, songs);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PlayerService.playQueue(TrackListActivity.this, songs, position);
                NowPlayingActivity.start(TrackListActivity.this);
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                SongActions.showMenu(TrackListActivity.this, songs.get(position), new SongActions.Callback() {
                    @Override
                    public void onSongDeleted() {
                        new LoadTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                });
                return true;
            }
        });

        findViewById(R.id.btn_play_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (songs.isEmpty()) return;
                PlayerService.playQueue(TrackListActivity.this, songs, 0);
            }
        });
        findViewById(R.id.btn_shuffle_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (songs.isEmpty()) return;
                PlayerService.playQueue(TrackListActivity.this, songs, 0);
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

        new LoadTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onNowPlayingChanged(long currentSongId) {
        if (adapter != null) adapter.setCurrentlyPlayingId(currentSongId);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class LoadTask extends AsyncTask<Void, Void, List<Song>> {
        @Override
        protected List<Song> doInBackground(Void... voids) {
            List<Song> result;
            int mode = getIntent().getIntExtra(EXTRA_MODE, MODE_ALBUM);
            if (mode == MODE_ALBUM) {
                long albumId = getIntent().getLongExtra(EXTRA_ALBUM_ID, -1);
                result = MediaScanner.getSongsForAlbum(TrackListActivity.this, albumId);
            } else {
                String artistName = getIntent().getStringExtra(EXTRA_ARTIST_NAME);
                result = MediaScanner.getSongsForArtistName(TrackListActivity.this, artistName);
            }
            // Та же папка-фильтрация, что и в "Все треки"/"Альбомы"/"Исполнители" —
            // иначе тут вылезали бы треки вне выбранных папок, хотя списки
            // альбомов/исполнителей их уже не показывают.
            result = MediaScanner.filterByFolders(result, SortPrefs.getLibraryFolders(TrackListActivity.this));
            for (Song s : result) {
                MediaScanner.resolveMissingTags(s);
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
    }
}
