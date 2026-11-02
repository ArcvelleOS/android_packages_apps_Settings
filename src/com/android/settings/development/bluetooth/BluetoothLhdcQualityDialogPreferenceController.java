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

package com.android.settings.development.bluetooth;

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecType;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settings.development.BluetoothA2dpConfigStore;
import com.android.settingslib.core.lifecycle.Lifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog preference controller to set the Bluetooth A2DP config of LHDC quality
 *
 * <p>The LHDC quality mode is carried in {@link BluetoothCodecConfig#getCodecSpecific1()}, laid
 * out as in the Bluetooth stack (a2dp_vendor_lhdcv5_constants.h): the quality mode in the low
 * byte tagged with {@link #QUALITY_MAGIC_NUM}, and the headset max/min bit rate reported back
 * by the stack in bits 8-11 and 16-19.
 */
public class BluetoothLhdcQualityDialogPreferenceController extends
        AbstractBluetoothDialogPreferenceController {

    private static final String KEY = "bluetooth_a2dp_lhdc_playback_quality";
    private static final String TAG = "BtLhdcQualityCtr";

    @VisibleForTesting static final long QUALITY_MAGIC_NUM = 0x8000L;
    private static final long VENDOR_CMD_MASK = 0xC000L;
    private static final long QUALITY_MASK = 0xFFL;
    private static final long PEER_MAX_BITRATE_MASK = 0xF00L;
    private static final int PEER_MAX_BITRATE_SHIFT = 8;
    private static final long PEER_MIN_BITRATE_MASK = 0xF0000L;
    private static final int PEER_MIN_BITRATE_SHIFT = 16;

    // Dialog index to quality mode (A2DP_LHDC_QUALITY_*): adaptive, then 1000 down to 64 kbps.
    @VisibleForTesting static final int[] QUALITY_MODES = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
    // Dialog index to bit rate in kbps, 0 for adaptive.
    private static final int[] BITRATES_KBPS = {0, 1000, 900, 500, 400, 320, 256, 192, 160, 64};

    public BluetoothLhdcQualityDialogPreferenceController(Context context, Lifecycle lifecycle,
                                                          BluetoothA2dpConfigStore store) {
        super(context, lifecycle, store);
    }

    @Override
    public String getPreferenceKey() {
        return KEY;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        ((BaseBluetoothDialogPreference) mPreference).setCallback(this);
    }

    @Override
    protected void writeConfigurationValues(final int index) {
        // Keep the other codec specific bits, only replace the quality mode.
        final BluetoothCodecConfig config = getCurrentCodecConfig();
        long codecSpecific1Value = config == null ? 0 : config.getCodecSpecific1();
        final int quality = (index >= 0 && index < QUALITY_MODES.length)
                ? QUALITY_MODES[index] : QUALITY_MODES[getDefaultIndex()];
        codecSpecific1Value &= ~(VENDOR_CMD_MASK | QUALITY_MASK);
        codecSpecific1Value |= QUALITY_MAGIC_NUM | quality;
        mBluetoothA2dpConfigStore.setCodecSpecific1Value(codecSpecific1Value);
    }

    @Override
    protected int getCurrentIndexByConfig(BluetoothCodecConfig config) {
        if (config == null) {
            Log.e(TAG, "Unable to get current config index. Config is null.");
            return getDefaultIndex();
        }
        return convertCfgToBtnIndex(config.getCodecSpecific1());
    }

    @Override
    public List<Integer> getSelectableIndex() {
        List<Integer> selectableIndex = new ArrayList<>();
        // Adaptive bit rate is always available.
        selectableIndex.add(0);

        final BluetoothCodecConfig config = getCurrentCodecConfig();
        final long codecSpecific1 = config == null ? 0 : config.getCodecSpecific1();
        int min = getPeerMinBitrate(codecSpecific1);
        int max = getPeerMaxBitrate(codecSpecific1);
        if (config != null) {
            // Same limits as the stack applies to fixed bit rates per sample rate.
            final int sampleRate = config.getSampleRate();
            if ((sampleRate & (BluetoothCodecConfig.SAMPLE_RATE_96000
                    | BluetoothCodecConfig.SAMPLE_RATE_192000)) != 0) {
                min = Math.max(min, 256);
            }
            if ((sampleRate & (BluetoothCodecConfig.SAMPLE_RATE_44100
                    | BluetoothCodecConfig.SAMPLE_RATE_48000)) != 0) {
                max = Math.min(max, 900);
            }
        }
        for (int i = 1; i < BITRATES_KBPS.length; i++) {
            if (BITRATES_KBPS[i] >= min && BITRATES_KBPS[i] <= max) {
                selectableIndex.add(i);
            }
        }
        return selectableIndex;
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        // Enable preference when current codec type is LHDC. For other cases, disable it.
        if (isLhdcV5(getCurrentCodecConfig())) {
            preference.setEnabled(true);
        } else {
            preference.setEnabled(false);
            preference.setSummary("");
        }
    }

    @Override
    public void onHDAudioEnabled(boolean enabled) {
        Log.d(TAG, "onHDAudioEnabled: " + enabled);
        mPreference.setEnabled(enabled && isLhdcV5(getCurrentCodecConfig()));
    }

    @VisibleForTesting
    int convertCfgToBtnIndex(long config) {
        if ((config & VENDOR_CMD_MASK) != QUALITY_MAGIC_NUM) {
            return getDefaultIndex();
        }
        final int quality = (int) (config & QUALITY_MASK);
        for (int i = 0; i < QUALITY_MODES.length; i++) {
            if (QUALITY_MODES[i] == quality) {
                return i;
            }
        }
        return getDefaultIndex();
    }

    @VisibleForTesting
    static boolean isLhdcV5(@Nullable BluetoothCodecConfig config) {
        if (config == null) {
            return false;
        }
        final BluetoothCodecType type = config.getExtendedCodecType();
        return type != null && type.getCodecId() == BluetoothCodecType.CODEC_ID_LHDCV5;
    }

    // Highest bit rate (kbps) the headset accepts. No limit is reported as 1000.
    private static int getPeerMaxBitrate(long codecSpecific1) {
        switch ((int) ((codecSpecific1 & PEER_MAX_BITRATE_MASK) >> PEER_MAX_BITRATE_SHIFT)) {
            case 0x1:
                return 400;
            case 0x2:
                return 500;
            case 0x3:
                return 900;
            default:
                return 1000;
        }
    }

    // Lowest bit rate (kbps) the headset accepts. No limit is reported as 64.
    private static int getPeerMinBitrate(long codecSpecific1) {
        switch ((int) ((codecSpecific1 & PEER_MIN_BITRATE_MASK) >> PEER_MIN_BITRATE_SHIFT)) {
            case 0x4:
                return 160;
            case 0x8:
                return 256;
            case 0xC:
                return 400;
            default:
                return 64;
        }
    }
}
