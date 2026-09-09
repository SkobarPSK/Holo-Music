package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

/** Настройки — раздел "Библиотека": папки, пересканирование, фильтры сканирования. */
public class SettingsLibraryActivity extends Activity {

    private static final int REQUEST_PICK_FOLDER = 200;
    private static final String[] SCAN_EXTENSIONS = {"mp3", "flac", "ogg", "m4a", "aac", "wav", "wma", "opus"};

    private LinearLayout folderListContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_library);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings_category_library);
        }

        setupFolderSection();
        setupRescanSection();
        setupScanFiltersSection();
    }

    // ---------- Папки библиотеки ----------

    private void setupFolderSection() {
        folderListContainer = findViewById(R.id.folder_list_container);
        findViewById(R.id.btn_add_folder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(SettingsLibraryActivity.this, FolderBrowserActivity.class), REQUEST_PICK_FOLDER);
            }
        });
        refreshFolderList();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(FolderBrowserActivity.EXTRA_SELECTED_PATH);
            if (path != null) {
                SortPrefs.addLibraryFolder(this, path);
                refreshFolderList();
            }
        }
    }

    private void refreshFolderList() {
        folderListContainer.removeAllViews();
        List<String> folders = SortPrefs.getLibraryFolders(this);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (final String folder : folders) {
            View row = inflater.inflate(R.layout.list_item_folder, folderListContainer, false);
            TextView pathText = row.findViewById(R.id.text_folder_path);
            TextView removeBtn = row.findViewById(R.id.btn_remove_folder);
            pathText.setText(folder);
            removeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SortPrefs.removeLibraryFolder(SettingsLibraryActivity.this, folder);
                    refreshFolderList();
                }
            });
            folderListContainer.addView(row);
        }
    }

    // ---------- Пересканирование библиотеки ----------

    private void setupRescanSection() {
        final TextView button = findViewById(R.id.btn_rescan_library);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                button.setEnabled(false);
                button.setText(R.string.rescanning);
                new AsyncTask<Void, Void, Integer>() {
                    @Override
                    protected Integer doInBackground(Void... voids) {
                        return LibraryRepository.rescan(SettingsLibraryActivity.this).size();
                    }

                    @Override
                    protected void onPostExecute(Integer count) {
                        button.setEnabled(true);
                        button.setText(R.string.rescan_library);
                        Toast.makeText(SettingsLibraryActivity.this,
                                getString(R.string.rescan_complete, count),
                                Toast.LENGTH_SHORT).show();
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        });
    }

    // ---------- Фильтры сканирования: типы файлов + минимальная длина ----------

    private void setupScanFiltersSection() {
        LinearLayout container = findViewById(R.id.filetype_checkbox_container);
        Set<String> excluded = SortPrefs.getExcludedExtensions(this);

        for (final String ext : SCAN_EXTENSIONS) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText("." + ext);
            checkBox.setTextColor(getResources().getColor(R.color.text_primary));
            checkBox.setChecked(!excluded.contains(ext));
            checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    SortPrefs.setExtensionExcluded(SettingsLibraryActivity.this, ext, !isChecked);
                }
            });
            container.addView(checkBox);
        }

        final EditText minDurationEdit = findViewById(R.id.edit_min_duration);
        int currentMin = SortPrefs.getMinDurationSeconds(this);
        minDurationEdit.setText(currentMin > 0 ? String.valueOf(currentMin) : "");
        minDurationEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int value = 0;
                try {
                    if (s.length() > 0) value = Integer.parseInt(s.toString());
                } catch (NumberFormatException ignored) {
                }
                SortPrefs.setMinDurationSeconds(SettingsLibraryActivity.this, value);
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
