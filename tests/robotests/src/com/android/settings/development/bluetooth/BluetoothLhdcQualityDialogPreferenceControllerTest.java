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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceScreen;

import com.android.settings.development.BluetoothA2dpConfigStore;
import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class BluetoothLhdcQualityDialogPreferenceControllerTest {

    private static final String DEVICE_ADDRESS = "00:11:22:33:44:55";
    // Native codec index of LHDCV5, as reported by the Bluetooth stack.
    private static final int LHDCV5_NATIVE_CODEC_INDEX = 7;
    // codec_specific_1: quality tag, peer max 500 kbps (0x2 << 8), peer min 160 kbps (0x4 << 16).
    private static final long PEER_MAX_500 = 0x200L;
    private static final long PEER_MIN_160 = 0x40000L;

    @Mock
    private BluetoothA2dp mBluetoothA2dp;
    @Mock
    private BluetoothAdapter mBluetoothAdapter;
    @Mock
    private PreferenceScreen mScreen;

    private BluetoothLhdcQualityDialogPreferenceController mController;
    private BluetoothLhdcQualityDialogPreference mPreference;
    private BluetoothA2dpConfigStore mBluetoothA2dpConfigStore;
    private BluetoothCodecConfig mCodecConfigAAC;
    private BluetoothDevice mActiveDevice;
    private Context mContext;
    private LifecycleOwner mLifecycleOwner;
    private Lifecycle mLifecycle;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mLifecycleOwner = () -> mLifecycle;
        mLifecycle = new Lifecycle(mLifecycleOwner);
        mBluetoothA2dpConfigStore = spy(new BluetoothA2dpConfigStore());
        mActiveDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(DEVICE_ADDRESS);
        mController = new BluetoothLhdcQualityDialogPreferenceController(mContext, mLifecycle,
                mBluetoothA2dpConfigStore);
        mController.mBluetoothAdapter = mBluetoothAdapter;
        mPreference = new BluetoothLhdcQualityDialogPreference(mContext);
        when(mScreen.findPreference(mController.getPreferenceKey())).thenReturn(mPreference);
        mController.displayPreference(mScreen);
        mCodecConfigAAC = new BluetoothCodecConfig.Builder()
                .setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC)
                .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_48000)
                .build();
        when(mBluetoothAdapter.getActiveDevices(eq(BluetoothProfile.A2DP)))
                .thenReturn(Arrays.asList(mActiveDevice));
    }

    private BluetoothCodecConfig lhdcConfig(int sampleRate, long codecSpecific1) {
        return new BluetoothCodecConfig.Builder()
                .setExtendedCodecType(new BluetoothCodecType(LHDCV5_NATIVE_CODEC_INDEX,
                        BluetoothCodecType.CODEC_ID_LHDCV5, "LHDCV5"))
                .setSampleRate(sampleRate)
                .setCodecSpecific1(codecSpecific1)
                .build();
    }

    private void connectWithCurrentConfig(BluetoothCodecConfig current) {
        BluetoothCodecStatus codecStatus = new BluetoothCodecStatus.Builder()
                .setCodecConfig(current)
                .setCodecsSelectableCapabilities(Arrays.asList(mCodecConfigAAC, current))
                .build();
        when(mBluetoothA2dp.getCodecStatus(mActiveDevice)).thenReturn(codecStatus);
        mController.onBluetoothServiceConnected(mBluetoothA2dp);
    }

    @Test
    public void writeConfigurationValues_checkQuality() {
        // Dialog index to A2DP_LHDC_QUALITY_*: adaptive (9), then 1000 kbps (8) down to 64 kbps (0)
        final int[] expectedModes = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
        for (int i = 0; i < expectedModes.length; i++) {
            mController.writeConfigurationValues(i);
            verify(mBluetoothA2dpConfigStore).setCodecSpecific1Value(0x8000L | expectedModes[i]);
        }
    }

    @Test
    public void writeConfigurationValues_keepsOtherBitsOfCurrentConfig() {
        connectWithCurrentConfig(lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_96000,
                0x8005L | PEER_MAX_500 | PEER_MIN_160));

        mController.writeConfigurationValues(2);

        verify(mBluetoothA2dpConfigStore)
                .setCodecSpecific1Value(0x8007L | PEER_MAX_500 | PEER_MIN_160);
    }

    @Test
    public void getCurrentIndexByConfig_verifyIndex() {
        assertThat(mController.getCurrentIndexByConfig(
                lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_96000, 0x8009L))).isEqualTo(0);
        assertThat(mController.getCurrentIndexByConfig(
                lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_96000, 0x8007L))).isEqualTo(2);
        assertThat(mController.getCurrentIndexByConfig(
                lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_96000, 0x8000L))).isEqualTo(9);
        // Peer capability bits must not change the index.
        assertThat(mController.getCurrentIndexByConfig(
                lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_96000,
                        0x8006L | PEER_MAX_500 | PEER_MIN_160))).isEqualTo(3);
    }

    @Test
    public void getCurrentIndexByConfig_noQualityTag_isAdaptive() {
        assertThat(mController.convertCfgToBtnIndex(0)).isEqualTo(0);
        // A value written for another codec (LDAC quality) must not be read as an LHDC mode.
        assertThat(mController.convertCfgToBtnIndex(1001)).isEqualTo(0);
    }

    @Test
    public void getSelectableIndex_peerLimits() {
        connectWithCurrentConfig(lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_48000,
                0x8009L | PEER_MAX_500 | PEER_MIN_160));

        // adaptive, 500, 400, 320, 256, 192, 160
        assertThat(mController.getSelectableIndex())
                .containsExactly(0, 3, 4, 5, 6, 7, 8).inOrder();
    }

    @Test
    public void getSelectableIndex_lowSampleRate_capsAt900() {
        connectWithCurrentConfig(lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_48000, 0x8009L));

        // everything but 1000 kbps
        assertThat(mController.getSelectableIndex())
                .containsExactly(0, 2, 3, 4, 5, 6, 7, 8, 9).inOrder();
    }

    @Test
    public void getSelectableIndex_highSampleRate_minimum256() {
        connectWithCurrentConfig(lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_96000, 0x8009L));

        // adaptive, 1000, 900, 500, 400, 320, 256
        assertThat(mController.getSelectableIndex())
                .containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder();
    }

    @Test
    public void updateState_codeTypeIsLHDC_enablePreference() {
        connectWithCurrentConfig(lhdcConfig(BluetoothCodecConfig.SAMPLE_RATE_96000, 0x8009L));
        mController.updateState(mPreference);

        assertThat(mPreference.isEnabled()).isTrue();
    }

    @Test
    public void updateState_codeTypeAAC_disablePreference() {
        connectWithCurrentConfig(mCodecConfigAAC);
        mController.updateState(mPreference);

        assertThat(mPreference.isEnabled()).isFalse();
    }
}
