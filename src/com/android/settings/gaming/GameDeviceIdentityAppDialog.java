/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.gaming;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Restorable app picker, styled like the profile picker, for apps not detected as games. */
public class GameDeviceIdentityAppDialog extends DialogFragment {
    static final String RESULT_KEY = "game_identity_app_result";
    static final String RESULT_PACKAGE = "package_name";
    private static final String ARG_LISTED = "listed_packages";

    static GameDeviceIdentityAppDialog newInstance(ArrayList<String> listedPackages) {
        final Bundle args = new Bundle();
        args.putStringArrayList(ARG_LISTED, listedPackages);
        final GameDeviceIdentityAppDialog fragment = new GameDeviceIdentityAppDialog();
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        final PackageManager packageManager = context.getPackageManager();
        final List<String> listed = requireArguments().getStringArrayList(ARG_LISTED);
        final Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        final Map<String, AppEntry> candidates = new HashMap<>();
        for (ResolveInfo resolveInfo : packageManager.queryIntentActivities(launcher, 0)) {
            final ApplicationInfo app = resolveInfo.activityInfo.applicationInfo;
            if (app.enabled && (!app.isSystemApp() || app.isUpdatedSystemApp())
                    && !app.packageName.equals(context.getPackageName())
                    && (listed == null || !listed.contains(app.packageName))) {
                candidates.put(app.packageName,
                        new AppEntry(app, app.loadLabel(packageManager).toString()));
            }
        }
        final List<AppEntry> apps = new ArrayList<>(candidates.values());
        apps.sort(Comparator.comparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER));

        return new AlertDialog.Builder(context)
                .setTitle(R.string.game_device_identity_add_app_title)
                .setAdapter(new AppAdapter(context, apps), (dialog, position) -> {
                    final Bundle result = new Bundle();
                    result.putString(RESULT_PACKAGE, apps.get(position).info.packageName);
                    getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        final ListView list = ((AlertDialog) requireDialog()).getListView();
        final int inset = getResources().getDimensionPixelSize(
                R.dimen.game_device_identity_dialog_inset);
        final int gap = getResources().getDimensionPixelSize(
                R.dimen.game_device_identity_choice_gap);
        list.setPadding(inset, gap, inset, gap);
        list.setClipToPadding(false);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(gap);
    }

    private static final class AppEntry {
        final ApplicationInfo info;
        final String label;

        AppEntry(ApplicationInfo info, String label) {
            this.info = info;
            this.label = label;
        }
    }

    private static final class AppAdapter extends BaseAdapter {
        private final Context mContext;
        private final List<AppEntry> mApps;

        AppAdapter(Context context, List<AppEntry> apps) {
            mContext = context;
            mApps = apps;
        }

        @Override
        public int getCount() {
            return mApps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return mApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View row = convertView != null ? convertView
                    : LayoutInflater.from(mContext).inflate(
                            R.layout.game_device_identity_app_choice, parent, false);
            final AppEntry entry = getItem(position);
            ((ImageView) row.findViewById(R.id.game_identity_app_icon)).setImageDrawable(
                    entry.info.loadIcon(mContext.getPackageManager()));
            ((TextView) row.findViewById(R.id.game_identity_app_title)).setText(entry.label);
            ((TextView) row.findViewById(R.id.game_identity_app_summary)).setText(
                    entry.info.packageName);
            return row;
        }
    }
}
