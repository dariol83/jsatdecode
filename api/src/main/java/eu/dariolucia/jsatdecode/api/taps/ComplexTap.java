package eu.dariolucia.jsatdecode.api.taps;

/**
 * Bounded latest-data observation; old samples may be overwritten, without producer waits.
 */
public interface ComplexTap {
    /**
     * Copies at most the requested recent samples on the observer thread.
     *
     * @param maximumSamples nonnegative limit; zero returns an empty snapshot
     * @return immutable snapshot, possibly shorter than requested
     * @throws IllegalArgumentException if the limit is negative
     */
    ComplexSnapshot snapshot(int maximumSamples);
}