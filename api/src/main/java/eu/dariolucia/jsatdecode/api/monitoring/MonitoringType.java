package eu.dariolucia.jsatdecode.api.monitoring;

/**
 * Stable metric value categories.
 */
public enum MonitoringType {
    /**
     * Boolean value.
     */
    BOOLEAN,
    /**
     * Signed 32-bit integer.
     */
    INTEGER,
    /**
     * Signed 64-bit integer.
     */
    LONG,
    /**
     * Double, including NaN for unavailable estimates.
     */
    DOUBLE,
    /**
     * Text value.
     */
    STRING,
    /**
     * Enumerated state.
     */
    ENUM
}