package eu.dariolucia.jsatdecode.api.source;

/**
 * Indexed, read-only access. A scoped instance is valid only during its
 * synchronous callback and must reject use outside it or from another thread.
 * Independently owned immutable blocks may be retained and shared. The backing
 * storage is never exposed.
 */
public interface IqReadAccess {
    /** Returns the valid length.
     * @return number of complex samples */
    int complexSamples();
    /** Reads the in-phase component.
     * @param index index within the valid window
     * @return in-phase component */
    float i(int index);
    /** Reads the quadrature component.
     * @param index index within the valid window
     * @return quadrature component */
    float q(int index);
}
