package com.github.skobsrpsk.holomusic;

import android.app.Fragment;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.adapter.AlbumAdapter;
import com.github.skobsrpsk.holomusic.model.Album;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка ALBUMS — список альбомов, при нажатии открывается TrackListActivity.
 * Альбомы строятся группировкой реальных отфильтрованных треков, а не
 * отдельной таблицей MediaStore.Audio.Albums (см. MediaScanner.getAlbumsFromSongs).
 */
public class AlbumsFragment extends Fragment {

    private final List<Album> albums = new ArrayList<>();
    private AlbumAdapter adapter;
    private TextView emptyText;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_song_list, container, false);
        ListView listView = root.findViewById(R.id.list_view);
        emptyText = root.findViewById(R.id.empty_text);

        adapter = new AlbumAdapter(getActivity(), albums);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Album album = albums.get(position);
                Intent intent = new Intent(getActivity(), TrackListActivity.class);
                intent.putExtra(TrackListActivity.EXTRA_MODE, TrackListActivity.MODE_ALBUM);
                intent.putExtra(TrackListActivity.EXTRA_ALBUM_ID, album.id);
                intent.putExtra(TrackListActivity.EXTRA_TITLE, album.name);
                startActivity(intent);
            }
        });

        new LoadTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        return root;
    }

    private class LoadTask extends AsyncTask<Void, Void, List<Album>> {
        @Override
        protected List<Album> doInBackground(Void... voids) {
            if (getActivity() == null) return new ArrayList<>();
            List<com.github.skobsrpsk.holomusic.model.Song> songs = LibraryRepository.getSongs(getActivity(), MediaScanner.SORT_TITLE);
            songs = MediaScanner.filterByFolders(songs, SortPrefs.getLibraryFolders(getActivity()));
            return MediaScanner.getAlbumsFromSongs(songs);
        }

        @Override
        protected void onPostExecute(List<Album> result) {
            if (getActivity() == null) return;
            albums.clear();
            albums.addAll(result);
            adapter.notifyDataSetChanged();
            emptyText.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }
}
