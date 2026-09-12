package com.github.skobsrpsk.holomusic;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class PlaylistsFragment extends Fragment implements Searchable {

    private final List<String> playlistNames = new ArrayList<>();
    // Полный список — filter() режет из него в playlistNames (который держит adapter).
    private final List<String> allPlaylistNames = new ArrayList<>();
    private String currentQuery = "";
    private ArrayAdapter<String> adapter;
    private TextView emptyText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_song_list, container, false);
        ListView listView = root.findViewById(R.id.list_view);
        emptyText = root.findViewById(R.id.empty_text);
        emptyText.setText(R.string.playlists_empty);

        adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_list_item_1, playlistNames);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(getActivity(), PlaylistDetailActivity.class);
                intent.putExtra(PlaylistDetailActivity.EXTRA_NAME, playlistNames.get(position));
                startActivity(intent);
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(playlistNames.get(position));
                return true;
            }
        });

        reload();
        return root;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(Menu.NONE, 1001, Menu.NONE, R.string.new_playlist)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1001) {
            showCreateDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showCreateDialog() {
        final EditText input = new EditText(getActivity());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.new_playlist_hint);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.new_playlist)
                .setView(input)
                .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) {
                            PlaylistStore.createPlaylist(getActivity(), name);
                            reload();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final String name) {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.delete_playlist)
                .setMessage(getString(R.string.delete_playlist_confirm, name))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PlaylistStore.deletePlaylist(getActivity(), name);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public void reload() {
        if (getActivity() == null) return;
        allPlaylistNames.clear();
        allPlaylistNames.addAll(PlaylistStore.getPlaylistNames(getActivity()));
        applyFilter();
    }

    @Override
    public void filter(String query) {
        currentQuery = query == null ? "" : query;
        applyFilter();
    }

    private void applyFilter() {
        playlistNames.clear();
        if (currentQuery.trim().isEmpty()) {
            playlistNames.addAll(allPlaylistNames);
        } else {
            String q = currentQuery.toLowerCase();
            for (String name : allPlaylistNames) {
                if (name.toLowerCase().contains(q)) {
                    playlistNames.add(name);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyText != null) emptyText.setVisibility(playlistNames.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
