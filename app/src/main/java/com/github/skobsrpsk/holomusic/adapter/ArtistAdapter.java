package com.github.skobsrpsk.holomusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.R;
import com.github.skobsrpsk.holomusic.model.Artist;

import java.util.List;
import java.util.Locale;

public class ArtistAdapter extends ArrayAdapter<Artist> {

    private final LayoutInflater inflater;
    private String currentlyPlayingName; // сравнение без учёта регистра, null = ничего не играет

    public ArtistAdapter(Context context, List<Artist> artists) {
        super(context, 0, artists);
        inflater = LayoutInflater.from(context);
    }

    /** Помечает строку исполнителя как играющую сейчас (по primaryArtistName текущего трека). */
    public void setCurrentlyPlayingName(String primaryName) {
        String normalized = (primaryName == null || primaryName.isEmpty())
                ? null : primaryName.toLowerCase(Locale.ROOT);
        if (normalized == null ? currentlyPlayingName != null : !normalized.equals(currentlyPlayingName)) {
            currentlyPlayingName = normalized;
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

        Artist artist = getItem(position);
        if (artist != null) {
            holder.title.setText(artist.name);
            holder.subtitle.setText(artist.albumCount + " альбомов • " + artist.songCount + " треков");

            boolean isPlaying = currentlyPlayingName != null && artist.name != null
                    && currentlyPlayingName.equals(artist.name.toLowerCase(Locale.ROOT));
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
