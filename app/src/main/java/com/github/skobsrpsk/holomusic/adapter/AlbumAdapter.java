package com.github.skobsrpsk.holomusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.R;
import com.github.skobsrpsk.holomusic.model.Album;

import java.util.List;

public class AlbumAdapter extends ArrayAdapter<Album> {

    private final LayoutInflater inflater;
    private long currentlyPlayingAlbumId = -1;

    public AlbumAdapter(Context context, List<Album> albums) {
        super(context, 0, albums);
        inflater = LayoutInflater.from(context);
    }

    /** Помечает строку альбома как играющую сейчас (по albumId текущего трека). */
    public void setCurrentlyPlayingAlbumId(long albumId) {
        if (this.currentlyPlayingAlbumId != albumId) {
            this.currentlyPlayingAlbumId = albumId;
            notifyDataSetChanged();
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_two_line, parent, false);
            holder = new ViewHolder();
            holder.marker = convertView.findViewById(R.id.text_now_playing_marker);
            holder.title = convertView.findViewById(R.id.text_title);
            holder.subtitle = convertView.findViewById(R.id.text_subtitle);
            convertView.findViewById(R.id.text_favorite).setVisibility(View.GONE);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Album album = getItem(position);
        if (album != null) {
            holder.title.setText(album.name);
            holder.subtitle.setText(album.artist + " • " + album.songCount + " треков");

            boolean isPlaying = album.id == currentlyPlayingAlbumId;
            holder.marker.setVisibility(isPlaying ? View.VISIBLE : View.INVISIBLE);
            int color = getContext().getResources().getColor(isPlaying ? R.color.holo_blue : R.color.text_primary);
            holder.title.setTextColor(color);
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView marker;
        TextView title;
        TextView subtitle;
    }
}
