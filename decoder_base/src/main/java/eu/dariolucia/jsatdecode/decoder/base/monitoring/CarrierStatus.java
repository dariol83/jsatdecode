package eu.dariolucia.jsatdecode.decoder.base.monitoring;

/**
 * Immutable carrier observation. A decoder publishes one reference through its existing volatile
 * field at its existing report cadence; readers capture that reference once. No lock detection,
 * availability policy or estimator is implemented here.
 *
 * @param lock exact decoder lock string
 * @param offsetHz carrier offset in Hz, NaN when unavailable
 */
public record CarrierStatus(String lock, double offsetHz) {}
