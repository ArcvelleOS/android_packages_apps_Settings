/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.sound;

import android.app.settings.SettingsEnums;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.android.settings.R;
import com.android.settings.bluetooth.BluetoothCodecPreference;
import com.android.settings.bluetooth.Utils;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.development.BluetoothA2dpConfigStore;
import com.android.settings.development.BluetoothServiceConnectionListener;
import com.android.settings.development.bluetooth.BluetoothBitPerSampleDialogPreferenceController;
import com.android.settings.development.bluetooth.BluetoothChannelModeDialogPreferenceController;
import com.android.settings.development.bluetooth.BluetoothLhdcQualityDialogPreferenceController;
import com.android.settings.development.bluetooth.BluetoothQualityDialogPreferenceController;
import com.android.settings.development.bluetooth.BluetoothSampleRateDialogPreferenceController;
import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.core.AbstractPreferenceController;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Per-headset A2DP audio options, using the same controllers as Bluetooth developer options. */
public class BluetoothAudioOptionsSettings extends DashboardFragment {
    private static final String TAG = "BtAudioOptions";

    private final BluetoothA2dpConfigStore mConfigStore = new BluetoothA2dpConfigStore();
    private BluetoothAdapter mAdapter;
    private BluetoothA2dp mA2dp;
    private BluetoothCodecPreference mCodecPreference;
    private boolean mDestroyed;

    private final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    notifyControllers(BluetoothServiceConnectionListener::onBluetoothCodecUpdated);
                }
            };

    private final BluetoothProfile.ServiceListener mServiceListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (mDestroyed) {
                        mAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                        return;
                    }
                    mA2dp = (BluetoothA2dp) proxy;
                    notifyControllers(listener -> listener.onBluetoothServiceConnected(mA2dp));
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    mA2dp = null;
                    if (mDestroyed) {
                        return;
                    }
                    notifyControllers(
                            BluetoothServiceConnectionListener::onBluetoothServiceDisconnected);
                }
            };

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.SOUND;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.bluetooth_audio_options;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = requireContext();
        mCodecPreference = findPreference("bluetooth_audio_codec");
        mCodecPreference.refresh();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        mAdapter = manager == null ? null : manager.getAdapter();
        if (mAdapter != null) {
            mAdapter.getProfileProxy(context, mServiceListener, BluetoothProfile.A2DP);
        }
        IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        context.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        requireContext().unregisterReceiver(mReceiver);
        if (mAdapter != null && mA2dp != null) {
            mAdapter.closeProfileProxy(BluetoothProfile.A2DP, mA2dp);
            mA2dp = null;
        }
        super.onDestroy();
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        List<AbstractPreferenceController> controllers = new ArrayList<>();
        controllers.add(
                new BluetoothSampleRateDialogPreferenceController(
                        context, getSettingsLifecycle(), mConfigStore));
        controllers.add(
                new BluetoothBitPerSampleDialogPreferenceController(
                        context, getSettingsLifecycle(), mConfigStore));
        controllers.add(
                new BluetoothChannelModeDialogPreferenceController(
                        context, getSettingsLifecycle(), mConfigStore));
        controllers.add(
                new BluetoothQualityDialogPreferenceController(
                        context, getSettingsLifecycle(), mConfigStore));
        controllers.add(
                new BluetoothLhdcQualityDialogPreferenceController(
                        context, getSettingsLifecycle(), mConfigStore));
        return controllers;
    }

    private void notifyControllers(Consumer<BluetoothServiceConnectionListener> action) {
        Context context = getContext();
        if (mDestroyed || context == null) {
            return;
        }
        LocalBluetoothManager manager = Utils.getLocalBtManager(context);
        A2dpProfile profile = manager == null ? null : manager.getProfileManager().getA2dpProfile();
        mCodecPreference.configure(
                () -> mA2dp == null || profile == null ? null : profile.getActiveDevice(),
                () -> profile,
                true);
        mCodecPreference.refresh();
        for (List<AbstractPreferenceController> controllers : getPreferenceControllers()) {
            for (AbstractPreferenceController controller : controllers) {
                if (controller instanceof BluetoothServiceConnectionListener) {
                    action.accept((BluetoothServiceConnectionListener) controller);
                }
            }
        }
    }
}
