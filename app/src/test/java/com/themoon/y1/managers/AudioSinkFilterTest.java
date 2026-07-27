package com.themoon.y1.managers;

import static com.themoon.y1.managers.BluetoothAudioManager.MAJOR_CLASS_UNKNOWN;
import static com.themoon.y1.managers.BluetoothAudioManager.canSinkAudio;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothClass;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

/**
 * Guards the rule that keeps the reconnect watchdog off devices that cannot accept our audio.
 * Being wrong in the strict direction stops headphones connecting, so the permissive cases
 * matter as much as the rejection.
 */
public class AudioSinkFilterTest {

    private static final UUID A2DP_SINK = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB");
    private static final UUID A2DP_SOURCE = UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB");
    private static final List<UUID> NONE = Collections.emptyList();

    @Test
    public void acceptsDevicesAdvertisingA2dpSinkWhateverTheyClaimToBe() {
        // A sink UUID wins outright -- some headsets report an odd major class.
        assertTrue(canSinkAudio(Arrays.asList(A2DP_SINK), BluetoothClass.Device.Major.PHONE));
        assertTrue(canSinkAudio(Arrays.asList(A2DP_SINK), MAJOR_CLASS_UNKNOWN));
    }

    @Test
    public void acceptsAudioDevicesAndUnknownsBeforeSdpCompletes() {
        // AirPods report no UUIDs until SDP finishes; rejecting here would break pairing.
        assertTrue(canSinkAudio(NONE, BluetoothClass.Device.Major.AUDIO_VIDEO));
        assertTrue(canSinkAudio(NONE, MAJOR_CLASS_UNKNOWN));
        assertTrue(canSinkAudio(null, MAJOR_CLASS_UNKNOWN));
    }

    @Test
    public void rejectsPhonesAndComputersWhichAreAudioSources() {
        assertFalse(canSinkAudio(NONE, BluetoothClass.Device.Major.PHONE));
        assertFalse(canSinkAudio(NONE, BluetoothClass.Device.Major.COMPUTER));
        // A phone advertising only A2DP *source* is still not somewhere we can send audio.
        assertFalse(canSinkAudio(Arrays.asList(A2DP_SOURCE), BluetoothClass.Device.Major.PHONE));
    }
}
