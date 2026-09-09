package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.List;
import java.util.Set;

/** Настройки — раздел "Интерфейс": экран по умолчанию, боковое меню, сортировка, миниатюры. */
public class SettingsInterfaceActivity extends Activity {

    private LinearLayout drawerSettingsContainer;
    private TextView defaultScreenText;
    private List<DrawerSection> order;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_interface);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings_category_interface);
        }

        setupDefaultScreenSection();
        setupDrawerSection();
        setupSortSection();
        setupThumbnailsSection();
    }

    // ---------- Экран по умолчанию ----------

    private void setupDefaultScreenSection() {
        defaultScreenText = findViewById(R.id.text_default_screen);
        refreshDefaultScreenText();

        defaultScreenText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final DrawerSection[] all = DrawerSection.values();
                CharSequence[] labels = new CharSequence[all.length];
                for (int i = 0; i < all.length; i++) {
                    labels[i] = getString(all[i].titleRes);
                }
                new AlertDialog.Builder(SettingsInterfaceActivity.this)
                        .setTitle(R.string.default_screen_title)
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                SortPrefs.setDefaultScreen(SettingsInterfaceActivity.this, all[which]);
                                refreshDefaultScreenText();
                            }
                        })
                        .show();
            }
        });
    }

    private void refreshDefaultScreenText() {
        defaultScreenText.setText(getString(SortPrefs.getDefaultScreen(this).titleRes));
    }

    // ---------- Боковое меню: порядок + видимость ----------

    private void setupDrawerSection() {
        drawerSettingsContainer = findViewById(R.id.drawer_settings_container);
        order = SortPrefs.getDrawerOrder(this);
        refreshDrawerSettingsList();
    }

    private void refreshDrawerSettingsList() {
        drawerSettingsContainer.removeAllViews();
        Set<DrawerSection> hidden = SortPrefs.getHiddenSections(this);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < order.size(); i++) {
            final DrawerSection section = order.get(i);
            final int index = i;

            View row = inflater.inflate(R.layout.list_item_drawer_setting, drawerSettingsContainer, false);
            CheckBox checkBox = row.findViewById(R.id.checkbox_visible);
            TextView nameText = row.findViewById(R.id.text_section_name);
            View upBtn = row.findViewById(R.id.btn_move_up);
            View downBtn = row.findViewById(R.id.btn_move_down);

            nameText.setText(section.titleRes);
            checkBox.setChecked(!hidden.contains(section));

            checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    SortPrefs.setSectionHidden(SettingsInterfaceActivity.this, section, !isChecked);
                }
            });

            upBtn.setEnabled(index > 0);
            upBtn.setAlpha(index > 0 ? 1f : 0.3f);
            upBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (index > 0) {
                        java.util.Collections.swap(order, index, index - 1);
                        SortPrefs.setDrawerOrder(SettingsInterfaceActivity.this, order);
                        refreshDrawerSettingsList();
                    }
                }
            });

            downBtn.setEnabled(index < order.size() - 1);
            downBtn.setAlpha(index < order.size() - 1 ? 1f : 0.3f);
            downBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (index < order.size() - 1) {
                        java.util.Collections.swap(order, index, index + 1);
                        SortPrefs.setDrawerOrder(SettingsInterfaceActivity.this, order);
                        refreshDrawerSettingsList();
                    }
                }
            });

            drawerSettingsContainer.addView(row);
        }
    }

    // ---------- Сортировка ----------

    private void setupSortSection() {
        RadioGroup group = findViewById(R.id.radio_sort);
        RadioButton title = findViewById(R.id.radio_sort_title);
        RadioButton artist = findViewById(R.id.radio_sort_artist);
        RadioButton album = findViewById(R.id.radio_sort_album);

        int current = SortPrefs.getSortMode(this);
        if (current == MediaScanner.SORT_ARTIST) artist.setChecked(true);
        else if (current == MediaScanner.SORT_ALBUM) album.setChecked(true);
        else title.setChecked(true);

        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.radio_sort_artist) {
                    SortPrefs.setSortMode(SettingsInterfaceActivity.this, MediaScanner.SORT_ARTIST);
                } else if (checkedId == R.id.radio_sort_album) {
                    SortPrefs.setSortMode(SettingsInterfaceActivity.this, MediaScanner.SORT_ALBUM);
                } else {
                    SortPrefs.setSortMode(SettingsInterfaceActivity.this, MediaScanner.SORT_TITLE);
                }
            }
        });
    }

    // ---------- Миниатюры обложек в списках ----------

    private void setupThumbnailsSection() {
        Switch toggle = findViewById(R.id.switch_show_thumbnails);
        toggle.setChecked(SortPrefs.isShowThumbnailsInLists(this));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SortPrefs.setShowThumbnailsInLists(SettingsInterfaceActivity.this, isChecked);
            }
        });
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
