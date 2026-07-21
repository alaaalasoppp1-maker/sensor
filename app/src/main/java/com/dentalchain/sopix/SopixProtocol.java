package com.dentalchain.sopix;

/** Capture-arm sequence observed immediately before the 2,625,000-byte EP82 burst in 234.pcapng. */
public final class SopixProtocol {
    public static final class Step {
        public final int delayMs;
        public final boolean ackRequired;
        public final byte[] data;
        Step(int delayMs, boolean ackRequired, int... values) {
            this.delayMs = delayMs;
            this.ackRequired = ackRequired;
            this.data = new byte[values.length];
            for (int i = 0; i < values.length; i++) this.data[i] = (byte) values[i];
        }
        public int sequence() { return data[0] & 0xff; }
    }

    private SopixProtocol() {}

    // Exact second arm sequence in 234.pcapng; EP82 begins about 4.93 s after command 10.
    public static final Step[] ACQUIRE = new Step[] {
        new Step(0,  true, 0x01,0x84,0x00,0x01,0x00,0x00,0x00,0x00),
        new Step(18, true, 0x02,0x84,0x00,0x01,0x00,0x00,0x00,0x00),
        new Step(10, true, 0x03,0x00,0x00,0x00,0x00,0x00,0x00,0x00),
        new Step(1,  true, 0x04,0x88,0x00,0x04,0x00,0x00,0x00,0x00),
        new Step(1,  true, 0x05,0x86,0x01,0x00,0x00,0x00,0x00,0x00),
        new Step(1,  true, 0x06,0x88,0x00,0x04,0x00,0x00,0x00,0x00),
        new Step(1,  true, 0x07,0x86,0x01,0x00,0x00,0x00,0x00,0x00),
        new Step(15, true, 0x08,0x84,0x00,0x01,0x00,0x00,0x00,0x00),
        new Step(80, true, 0x09,0x1b,0x00,0x00,0x00,0x00,0x1f,0xff),
        // The Windows trace contains 0A 00. Some Android USB stacks lose this tiny final reply;
        // sending command 10 successfully is enough to continue waiting for EP82.
        new Step(1, false, 0x0a,0x03,0x00,0x00,0x00,0x00,0x00,0x00)
    };
}
