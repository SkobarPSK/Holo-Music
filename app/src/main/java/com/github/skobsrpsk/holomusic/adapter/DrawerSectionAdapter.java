package com.github.skobsrpsk.holomusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.DrawerSection;
import com.github.skobsrpsk.holomusic.R;

import java.util.List;

public class DrawerSectionAdapter extends ArrayAdapter<DrawerSection> {

    private final LayoutInflater inflater;
    private DrawerSection selected;

    public DrawerSectionAdapter(Context context, List<DrawerSection> sections) {
        super(context, 0, sections);
        inflater = LayoutInflater.from(context);
    }

    public void setSelected(DrawerSection section) {
        this.selected = section;
        notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view;
        if (convertView == null) {
            view = (TextView) inflater.inflate(R.layout.list_item_drawer, parent, false);
        } else {
            view = (TextView) convertView;
        }

        DrawerSection section = getItem(position);
        if (section != null) {
            view.setText(section.titleRes);
            boolean isSelected = section == selected;
            view.setTextColor(getContext().getResources().getColor(
                    isSelected ? R.color.holo_blue : R.color.text_primary));
        }
        return view;
    }
}
