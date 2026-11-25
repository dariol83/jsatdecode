package eu.dariolucia.jsatdecode.inspector;

import java.nio.ByteBuffer;

/** Headerless interleaved IQ formats, serialized little-endian by the recording worker. */
enum RecordingFormat {
    CF32(".cf32", 8) {
        @Override
        void putSample(ByteBuffer bytes, float i, float q) {
            bytes.putInt(Float.floatToRawIntBits(i));
            bytes.putInt(Float.floatToRawIntBits(q));
        }
    },
    CS16(".cs16", 4) {
        @Override
        void putSample(ByteBuffer bytes, float i, float q) {
            bytes.putShort(quantize(i));
            bytes.putShort(quantize(q));
        }
    };

    private final String extension;
    private final int bytesPerComplexSample;

    RecordingFormat(String extension, int bytesPerComplexSample) {
        this.extension = extension;
        this.bytesPerComplexSample = bytesPerComplexSample;
    }

    String extension() { return extension; }
    int bytesPerComplexSample() { return bytesPerComplexSample; }

    // Only the writer invokes serialization; the supplied buffer is little-endian.
    abstract void putSample(ByteBuffer bytes, float i, float q);

    private static short quantize(float sample) {
        // Math.round maps NaN to zero and infinities to the long extrema.
        long rounded = Math.round(sample * 32768.0);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
    }
}
