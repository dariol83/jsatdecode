package eu.dariolucia.jsatdecode.api.source;

/**
 * Exclusive indexed write access valid only during the synchronous callback
 * that supplied it. It cannot be shared with another thread or retained after
 * the callback, and it does not expose the backing storage.
 */
public interface IqWriteAccess {
    /** Returns the writable capacity.
     * @return complex-sample capacity */
    int capacity();
    /** Writes one complex sample.
     * @param index complex-sample index within capacity
     * @param i in-phase component
     * @param q quadrature component */
    void put(int index, float i, float q);
}
