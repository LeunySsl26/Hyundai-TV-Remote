package com.hyundai.remote;

import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class IrTransmitter {
    private static final String TAG = "IrTransmitter";
    public static final int FREQUENCY_38KHZ = 38000;

    public enum Profile {
        HYUNDAI_WEBOS("Hyundai webOS (LG 0x04) [Recomendado]", 0x04),
        HYUNDAI_GENERIC_1("Hyundai Generic NEC 1 (0x00)", 0x00),
        HYUNDAI_GENERIC_2("Hyundai Generic NEC 2 (0x01)", 0x01),
        HYUNDAI_GENERIC_3("Hyundai Generic NEC 3 (0x02)", 0x02);

        public final String name;
        public final int address;

        Profile(String name, int address) {
            this.name = name;
            this.address = address;
        }
    }

    // Command definitions for Hyundai webOS / LG NEC
    public static final int CMD_POWER = 0x08;
    public static final int CMD_MUTE = 0x09;
    public static final int CMD_VOL_UP = 0x02;
    public static final int CMD_VOL_DOWN = 0x03;
    public static final int CMD_CH_UP = 0x00;
    public static final int CMD_CH_DOWN = 0x01;
    public static final int CMD_UP = 0x40;
    public static final int CMD_DOWN = 0x41;
    public static final int CMD_LEFT = 0x07;
    public static final int CMD_RIGHT = 0x06;
    public static final int CMD_OK = 0x44;
    public static final int CMD_HOME = 0x7C;
    public static final int CMD_BACK = 0x4C;
    public static final int CMD_BACK_ALT = 0x28;
    public static final int CMD_MENU = 0x43;
    public static final int CMD_EXIT = 0x5B;
    public static final int CMD_INPUT = 0x0B;

    public static final int CMD_PLAY = 0xB0;
    public static final int CMD_PAUSE = 0xBA;
    public static final int CMD_STOP = 0xB1;
    public static final int CMD_REWIND = 0x8F;
    public static final int CMD_FAST_FORWARD = 0x8E;

    public static final int CMD_RED = 0x72;
    public static final int CMD_GREEN = 0x71;
    public static final int CMD_YELLOW = 0x63;
    public static final int CMD_BLUE = 0x61;

    private final ConsumerIrManager irManager;
    private Profile currentProfile = Profile.HYUNDAI_WEBOS;

    public IrTransmitter(Context context) {
        this.irManager = (ConsumerIrManager) context.getSystemService(Context.CONSUMER_IR_SERVICE);
    }

    public boolean hasIrEmitter() {
        return irManager != null && irManager.hasIrEmitter();
    }

    public void setProfile(Profile profile) {
        this.currentProfile = profile;
    }

    public Profile getCurrentProfile() {
        return currentProfile;
    }

    public boolean transmitCommand(int command) {
        if (!hasIrEmitter()) {
            Log.e(TAG, "No IR emitter available on this device!");
            return false;
        }

        try {
            int[] pattern = buildNecPattern(currentProfile.address, command);
            irManager.transmit(FREQUENCY_38KHZ, pattern);
            Log.d(TAG, "IR command transmitted: 0x" + Integer.toHexString(command) + " (Address: 0x" + Integer.toHexString(currentProfile.address) + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error transmitting IR command", e);
            return false;
        }
    }

    public boolean transmitNumber(int number) {
        if (number == 0) return transmitCommand(0x10);
        if (number >= 1 && number <= 9) return transmitCommand(0x10 + number);
        return false;
    }

    /**
     * Builds standard 32-bit NEC IR microsecond timing pulse array:
     * - Leader: 9000us mark, 4500us space
     * - Address (8 bits LSB first)
     * - Inverted Address (8 bits LSB first)
     * - Command (8 bits LSB first)
     * - Inverted Command (8 bits LSB first)
     * - Stop bit: 560us mark, 40000us space
     */
    public static int[] buildNecPattern(int address, int command) {
        int invAddress = (~address) & 0xFF;
        int invCommand = (~command) & 0xFF;

        int[] dataBytes = new int[]{address, invAddress, command, invCommand};

        // 2 (header) + 32*2 (bits) + 2 (stop/leadout) = 68 intervals
        int[] pattern = new int[68];
        int idx = 0;

        // Leader
        pattern[idx++] = 9000;
        pattern[idx++] = 4500;

        // 32 Bits
        for (int b : dataBytes) {
            for (int bit = 0; bit < 8; bit++) {
                pattern[idx++] = 560; // Mark
                if (((b >> bit) & 1) == 1) {
                    pattern[idx++] = 1690; // Space for bit 1
                } else {
                    pattern[idx++] = 560;  // Space for bit 0
                }
            }
        }

        // Stop bit & trailing space
        pattern[idx++] = 560;
        pattern[idx++] = 40000;

        return pattern;
    }
}
