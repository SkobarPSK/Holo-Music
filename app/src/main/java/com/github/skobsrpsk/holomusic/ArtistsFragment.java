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

import com.github.skobsrpsk.holomusic.adapter.ArtistAdapter;
import com.github.skobsrpsk.holomusic.model.Artist;
import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка ARTISTS — список исполнителей, при нажатии открывается TrackListActivity.
 * Исполнители строятся группировкой реальных отфильтрованных треков (см.
 * MediaScanner.getArtistsFromSongs), а не отдельной таблицей
 * MediaStore.Audio.Artists — так список уважает ограничение по папкам
 * из настроек и не включает не-музыкальные "псевдо-исполнителей".
 */
public class ArtistsFragment extends Fragment implements NowPlayingAware {

    private final List<Artist> artists = new ArrayList<>();
    // Кэш последнего загруженного списка треков — нужен, чтобы по id
    // текущего трека найти его primaryArtistName() без повторного похода
    // в MediaStore на каждую смену трека.
    private final List<Song> loadedSongs = new ArrayList<>();
    private ArtistAdapter adapter;
    private TextView emptyText;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_song_list, container, false);
        ListView listView = root.findViewById(R.id.list_view);
        emptyText = root.findViewById(R.id.empty_text);

        adapter = new ArtistAdapter(getActivity(), artists);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Artist artist = artists.get(position);
                Intent intent = new Intent(getActivity(), TrackListActivity.class);
                intent.putExtra(TrackListActivity.EXTRA_MODE, TrackListActivity.MODE_ARTIST);
                intent.putExtra(TrackListActivity.EXTRA_ARTIST_ID, artist.id);
                intent.putExtra(TrackListActivity.EXTRA_ARTIST_NAME, artist.name);
                intent.putExtra(TrackListActivity.EXTRA_TITLE, artist.name);
                startActivity(intent);
            }
        });

        new LoadTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        return root;
    }

    private class LoadTask extends AsyncTask<Void, Void, List<Artist>> {
        @Override
        protected List<Artist> doInBackground(Void... voids) {
            if (getActivity() == null) return new ArrayList<>();
            List<Song> songs = LibraryRepository.getSongs(getActivity(), MediaScanner.SORT_TITLE);
            songs = MediaScanner.filterByFolders(songs, SortPrefs.getLibraryFolders(getActivity()));
            loadedSongs.clear();
            loadedSongs.addAll(songs);
            return MediaScanner.getArtistsFromSongs(songs);
        }

        @Override
        protected void onPostExecute(List<Artist> result) {
            if (getActivity() == null) return;
            artists.clear();
            artists.addAll(result);
            adapter.notifyDataSetChanged();
            emptyText.setVisibility(artists.isEmpty() ? View.VISIBLE : View.GONE);
            if (getActivity() instanceof BaseActivity) {
                BaseActivity base = (BaseActivity) getActivity();
                if (base.serviceBound) {
                    Song current = base.playerService.getCurrentSong();
                    onNowPlayingChanged(current != null ? current.id : -1);
                }
            }
        }
    }

    @Override
    public void onNowPlayingChanged(long currentSongId) {
        if (adapter == null) return;
        String primaryName = null;
        for (Song s : loadedSongs) {
            if (s.id == currentSongId) {
                primaryName = MediaScanner.primaryArtistName(s.artist);
                break;
            }
        }
        adapter.setCurrentlyPlayingName(primaryName);
    }
}
