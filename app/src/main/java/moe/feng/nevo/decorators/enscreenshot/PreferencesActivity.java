package moe.feng.nevo.decorators.enscreenshot;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import moe.feng.nevo.decorators.enscreenshot.utils.ActionUtils;
import moe.feng.nevo.decorators.enscreenshot.utils.Executors;

public class PreferencesActivity extends Activity {

    private static final String ACTION_UPDATE_SETTINGS =
            BuildConfig.APPLICATION_ID + ".action.UPDATE_SETTINGS";

    private static final String EXTRA_UPDATE_TYPE =
            BuildConfig.APPLICATION_ID + ".extra.UPDATE_TYPE";

    private static final String NEVOLUTION_PACKAGE = "com.oasisfeng.nevo";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new PreferencesFragment())
                    .commit();
        }

        CompletableFuture.supplyAsync(() -> {
            PackageInfo packageInfo = null;
            try {
                packageInfo = getPackageManager().getPackageInfo(NEVOLUTION_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException ignored) {

            }
            return packageInfo != null && NEVOLUTION_PACKAGE.equals(packageInfo.packageName);
        }).whenCompleteAsync((isNevoInstalled, thr) -> {
            if (!isNevoInstalled || thr != null) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.nevolution_missing_title)
                        .setMessage(R.string.nevolution_missing_content)
                        .setCancelable(false)
                        .setPositiveButton(R.string.go_to_google_play, (dialog, which) ->
                                ActionUtils.viewAppInMarket(this, NEVOLUTION_PACKAGE))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener(dialog -> {
                            if (!isFinishing()) {
                                finish();
                            }
                        })
                        .show();
            }
        }, Executors.getMainThreadExecutor());
    }

    public static class PreferencesFragment extends PreferenceFragment {

        private static final String KEY_SCREENSHOT_PATH = "screenshot_path";
        private static final String KEY_PREFERRED_EDITOR = "preferred_editor";

        private Preference mScreenshotPath;
        private Preference mPreferredEditor;
        private CheckBoxPreference mHideLauncherIcon;

        private ScreenshotPreferences mPreferences;

        private final Set<CompletableFuture<?>> mFutures = new LinkedHashSet<>();

        private final BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(@NonNull Context context, @Nullable Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }

                if (ACTION_UPDATE_SETTINGS.equals(intent.getAction())) {
                    switch (intent.getStringExtra(EXTRA_UPDATE_TYPE)) {
                        case KEY_SCREENSHOT_PATH:
                            updateUiScreenshotPath();
                            break;
                        case KEY_PREFERRED_EDITOR:
                            updateUiPreferredEditor();
                            break;
                        default:
                            Log.w("Preferences", "Unsupported update type.");
                    }
                }
            }
        };

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);

            mPreferences = new ScreenshotPreferences(getContext());

            mScreenshotPath = findPreference(KEY_SCREENSHOT_PATH);
            mPreferredEditor = findPreference(KEY_PREFERRED_EDITOR);
            mHideLauncherIcon = (CheckBoxPreference) findPreference("hide_launcher_icon");
            final Preference githubPref = findPreference("github_repo");

            updateUiScreenshotPath();
            updateUiPreferredEditor();
            updateUiHideLauncherIcon();

            mScreenshotPath.setOnPreferenceClickListener(this::setupScreenshotPath);
            mPreferredEditor.setOnPreferenceClickListener(this::setupPreferredEditor);
            mHideLauncherIcon.setOnPreferenceChangeListener(this::changeHideLauncherIcon);
            githubPref.setOnPreferenceClickListener(p -> {
                final Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(getString(R.string.pref_github_repo_url)));
                startActivity(intent);
                return true;
            });
        }

        @Override
        public void onResume() {
            getContext().registerReceiver(
                    mUpdateReceiver, new IntentFilter(ACTION_UPDATE_SETTINGS));
            super.onResume();
        }

        @Override
        public void onPause() {
            getContext().unregisterReceiver(mUpdateReceiver);
            super.onPause();
        }

        private boolean setupScreenshotPath(Preference p) {
            new ScreenshotPathEditDialog()
                    .show(getChildFragmentManager(), KEY_SCREENSHOT_PATH);
            return true;
        }

        private boolean setupPreferredEditor(Preference p) {
            new PreferredEditorChooserDialog()
                    .show(getChildFragmentManager(), KEY_PREFERRED_EDITOR);
            return true;
        }

        private boolean changeHideLauncherIcon(Preference p, Object o) {
            final boolean b = (Boolean) o;
            mPreferences.setHideLauncherIcon(b);
            return true;
        }

        @MainThread
        private void updateUiScreenshotPath() {
            mFutures.add(CompletableFuture
                    .supplyAsync(mPreferences::getScreenshotPath)
                    .thenApply(path ->
                            getString(R.string.pref_screenshots_store_path_summary_format, path))
                    .whenCompleteAsync((summary, thr) -> mScreenshotPath.setSummary(summary),
                            Executors.getMainThreadExecutor()));
        }

        @MainThread
        private void updateUiPreferredEditor() {
            mFutures.add(CompletableFuture
                    .supplyAsync(mPreferences::getPreferredEditorTitle)
                    .thenApply(optional ->
                            optional.orElseGet(() -> getString(R.string.ask_every_time)))
                    .thenApply(title ->
                            getString(R.string.pref_preferred_editor_summary, title))
                    .whenCompleteAsync((summary, thr) -> mPreferredEditor.setSummary(summary),
                            Executors.getMainThreadExecutor()));
        }

        @MainThread
        private void updateUiHideLauncherIcon() {
            mFutures.add(CompletableFuture
                    .supplyAsync(() -> mPreferences.isHideLauncherIcon())
                    .whenCompleteAsync((bool, thr) -> mHideLauncherIcon.setChecked(bool),
                            Executors.getMainThreadExecutor()));
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            for (Future future : mFutures) {
                if (!future.isCancelled() && !future.isDone()) {
                    future.cancel(true);
                }
            }
            mFutures.clear();
        }

        public static class ScreenshotPathEditDialog extends DialogFragment {

            private static final String STATE_EDIT_TEXT = "edit_text";

            private ScreenshotPreferences mPreferences;

            private EditText mEditText;

            @Override
            public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mPreferences = new ScreenshotPreferences(getContext());
            }

            @Override
            public Dialog onCreateDialog(Bundle savedInstanceState) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(R.string.pref_screenshots_store_path);
                final View view = LayoutInflater.from(builder.getContext())
                        .inflate(R.layout.dialog_layout_edit_text, null);
                mEditText = view.findViewById(android.R.id.edit);
                builder.setView(view);
                builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (TextUtils.isEmpty(mEditText.getText())) {
                        mPreferences.setScreenshotPath(null);
                    } else {
                        mPreferences.setScreenshotPath(mEditText.getText().toString());
                    }
                    getContext().sendBroadcast(new Intent(ACTION_UPDATE_SETTINGS)
                            .putExtra(EXTRA_UPDATE_TYPE, KEY_SCREENSHOT_PATH));
                });
                builder.setNegativeButton(android.R.string.cancel, null);

                if (savedInstanceState == null) {
                    mEditText.setText(mPreferences.getScreenshotPath());
                } else {
                    mEditText.onRestoreInstanceState(
                            savedInstanceState.getParcelable(STATE_EDIT_TEXT));
                }

                return builder.create();
            }

            @Override
            public void onSaveInstanceState(Bundle outState) {
                super.onSaveInstanceState(outState);
                if (mEditText != null) {
                    outState.putParcelable(STATE_EDIT_TEXT, mEditText.onSaveInstanceState());
                }
            }
        }

        public static class PreferredEditorChooserDialog extends DialogFragment {

            private ScreenshotPreferences mPreferences;

            private List<Pair<ComponentName, String>> mChoices;

            private int selected;

            @Override
            public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mPreferences = new ScreenshotPreferences(getContext());
            }

            @Override
            public Dialog onCreateDialog(Bundle savedInstanceState) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(R.string.pref_preferred_editor);
                // TODO: build list async
                mChoices = buildChoicesList(getContext());
                final Optional<ComponentName> current =
                        mPreferences.getPreferredEditorComponentName();
                if (current.isPresent() && mPreferences.isPreferredEditorAvailable()) {
                    for (int i = 1; i < mChoices.size(); i++) {
                        if (current.get().equals(mChoices.get(i).first)) {
                            selected = i;
                            break;
                        }
                    }
                }
                builder.setSingleChoiceItems(
                        mChoices.stream().map(p -> p.second).toArray(CharSequence[]::new),
                        selected,
                        (dialog, which) -> selected = which);
                builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mPreferences.setPreferredEditorComponentName(mChoices.get(selected).first);
                    getContext().sendBroadcast(new Intent(ACTION_UPDATE_SETTINGS)
                            .putExtra(EXTRA_UPDATE_TYPE, KEY_PREFERRED_EDITOR));
                });
                builder.setNegativeButton(android.R.string.cancel, null);
                return builder.create();
            }

            @NonNull
            private static List<Pair<ComponentName, String>> buildChoicesList(
                    @NonNull Context context) {
                final List<Pair<ComponentName, String>> result = new ArrayList<>();

                result.add(Pair.create(null, context.getString(R.string.ask_every_time)));

                final Intent intent = new Intent(Intent.ACTION_EDIT);
                intent.setType("image/*");
                final PackageManager pm = context.getPackageManager();
                final List<ResolveInfo> resolve = context.getPackageManager()
                        .queryIntentActivities(intent, PackageManager.GET_META_DATA);
                resolve.stream()
                        .map(item -> Pair.create(
                                ComponentName.createRelative(
                                        item.activityInfo.packageName, item.activityInfo.name),
                                item.loadLabel(pm).toString()
                        ))
                        .forEach(result::add);

                return result;
            }
        }
    }
}
