package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.monitoring.*;
import eu.dariolucia.jsatdecode.api.taps.ComplexSnapshot;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/* Observer-only demonstration fixture. Signals are generated when the observer
   requests a snapshot, never in DSP and never on the EDT. */
final class SyntheticUiSource implements Monitorable, TapProvider {
    private final long start = System.nanoTime();

    @Override
    public MonitoringSnapshot monitoringSnapshot() {
        long now = System.nanoTime();
        double elapsed = (now - start) / 1_000_000_000.0;
        boolean carrier = ((int) elapsed / 7) % 2 == 0;
        long frames = (long) (elapsed * 30);
        return new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(), Map.ofEntries(
                Map.entry("runtime.mode", "Synthetic demonstration"), Map.entry(DecoderStatusKeys.SIGNAL_SNR_DB, 5 + 8 * Math.sin(elapsed / 5)),
                Map.entry(DecoderStatusKeys.CARRIER_LOCK, carrier ? "LOCKED" : "SEARCHING"),
                Map.entry(DecoderStatusKeys.CARRIER_OFFSET_HZ, carrier ? 1250 * Math.sin(elapsed / 10) : Double.NaN),
                Map.entry(DecoderStatusKeys.SYMBOL_LOCK, ((int) elapsed / 11) % 2 == 0 ? "LOCKED" : "UNLOCKED"),
                Map.entry(DecoderStatusKeys.SYMBOL_RATE_BAUD, 2400.0),
                Map.entry(DecoderStatusKeys.FRAME_LOCK, ((int) elapsed / 13) % 2 == 0 ? "LOCKED" : "UNLOCKED"),
                Map.entry(DecoderStatusKeys.FRAME_GOOD, frames), Map.entry(DecoderStatusKeys.FRAME_BAD, 0L),
                Map.entry(DecoderStatusKeys.FRAME_TOTAL, frames), Map.entry(DecoderStatusKeys.FRAME_RATE_HZ, elapsed > 0 ? frames / elapsed : 0.0),
                Map.entry("source.active", true), Map.entry("source.center_frequency", 137100000.0)));
    }

    @Override
    public Collection<MonitoringDescriptor> monitoringDescriptors() {
        return List.of(
                new MonitoringDescriptor(DecoderStatusKeys.SIGNAL_SNR_DB, MonitoringType.DOUBLE, "dB", "Generated demonstration SNR"),
                new MonitoringDescriptor(DecoderStatusKeys.CARRIER_LOCK, MonitoringType.STRING, "", "Generated carrier state"),
                new MonitoringDescriptor(DecoderStatusKeys.CARRIER_OFFSET_HZ, MonitoringType.DOUBLE, "Hz", "Generated centre-relative carrier offset"),
                new MonitoringDescriptor(DecoderStatusKeys.SYMBOL_LOCK, MonitoringType.STRING, "", "Generated symbol state"),
                new MonitoringDescriptor(DecoderStatusKeys.SYMBOL_RATE_BAUD, MonitoringType.DOUBLE, "baud", "Generated symbol rate"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_LOCK, MonitoringType.STRING, "", "Generated frame state"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_GOOD, MonitoringType.LONG, "frames", "Generated accepted frame count"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_BAD, MonitoringType.LONG, "frames", "Generated rejected frame count"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_TOTAL, MonitoringType.LONG, "frames", "Generated classified frame count"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_RATE_HZ, MonitoringType.DOUBLE, "Hz", "Generated frames per signal second"));
    }

    @Override
    public Optional<ComplexTap> rf() {
        return Optional.of(maximum -> samples(maximum, 48000, 6000));
    }

    @Override
    public Optional<ComplexTap> baseband() {
        return Optional.of(maximum -> samples(maximum, 12000, -1500));
    }

    @Override
    public Optional<ComplexTap> symbols() {
        return Optional.of(maximum -> {
            int n = Math.min(maximum, 16384);
            float[] iq = new float[n * 2];
            for (int i = 0; i < n; i++) {
                // Deterministic QPSK clusters with small bounded display noise.
                iq[2 * i] = (float) (((i & 1) == 0 ? 1 : -1) * Math.sqrt(0.5) + 0.035 * Math.sin(i * 1.7));
                iq[2 * i + 1] = (float) (((i & 2) == 0 ? 1 : -1) * Math.sqrt(0.5) + 0.035 * Math.cos(i * 2.3));
            }
            return new ComplexSnapshot(iq, n, 2400, eu.dariolucia.jsatdecode.util.UtcTime.nowNanos());
        });
    }

    private ComplexSnapshot samples(int maximum, double rate, double frequency) {
        int size = Math.min(maximum, 16384);
        float[] iq = new float[size * 2];
        for (int i = 0; i < size; i++) {
            double angle = 2 * Math.PI * frequency * i / rate;
            iq[2 * i] = (float) Math.cos(angle);
            iq[2 * i + 1] = (float) Math.sin(angle);
        }
        return new ComplexSnapshot(iq, size, rate, eu.dariolucia.jsatdecode.util.UtcTime.nowNanos());
    }
}
