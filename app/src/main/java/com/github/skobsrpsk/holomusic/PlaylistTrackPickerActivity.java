package com.github.skobsrpsk.holomusic;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Список всех треков с чекбоксами — отмеченные состоят в плейлисте.
 * Тап по строке сразу добавляет/убирает трек, без отдельной кнопки "Готово".
 */
public class PlaylistTrackPickerActivity extends BaseActivity {

    public static final String EXTRA_PLAYLIST_NAME = "playlist_name";

    private String playlistName;
    private final List<Song> songs = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private PickerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setBaseContentView(R.layout.fragment_song_list);

        playlistName = getIntent().getStringExtra(EXTRA_PLAYLIST_NAME);
        selectedIds.addAll(PlaylistStore.getPlaylist(this, playlistName).songIds);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(playlistName);
        }

        ListView listView = findViewById(R.id.list_view);
        adapter = new PickerAdapter(this, songs);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Song song = songs.get(position);
                if (selectedIds.contains(song.id)) {
                    selectedIds.remove(song.id);
                    PlaylistStore.removeSongFromPlaylist(PlaylistTrackPickerActivity.this, playlistName, song.id);
                } else {
                    selectedIds.add(song.id);
                    PlaylistStore.addSongToPlaylist(PlaylistTrackPickerActivity.this, playlistName, song.id);
                }
                adapter.notifyDataSetChanged();
            }
        });

        new LoadTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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
            return LibraryRepository.getSongs(PlaylistTrackPickerActivity.this, MediaScanner.SORT_TITLE);
        }

        @Override
        protected void onPostExecute(List<Song> result) {
            songs.clear();
            songs.addAll(result);
            adapter.notifyDataSetChanged();
        }
    }

    private class PickerAdapter extends ArrayAdapter<Song> {
        private final LayoutInflater inflater;

        PickerAdapter(android.content.Context context, List<Song> songs) {
            super(context, 0, songs);
            inflater = LayoutInflater.from(context);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.list_item_checkable, parent, false);
            }
            TextView title = convertView.findViewById(R.id.text_title);
            TextView subtitle = convertView.findViewById(R.id.text_subtitle);
            CheckBox checkBox = convertView.findViewById(R.id.checkbox_selected);

            Song song = getItem(position);
            if (song != null) {
                title.setText(song.title);
                subtitle.setText(song.artist + " — " + song.album);
                checkBox.setChecked(selectedIds.contains(song.id));
            }
            return convertView;
        }
    }
}
