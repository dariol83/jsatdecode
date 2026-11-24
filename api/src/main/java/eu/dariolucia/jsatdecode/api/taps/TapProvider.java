package eu.dariolucia.jsatdecode.api.taps;

import java.util.Optional;

/**
 * Optional bounded taps; obtaining or reading them must never pace DSP.
 */
public interface TapProvider {
    /**
     * Returns decoder input or documented RF preprocessing tap.
     *
     * @return RF tap if available
     */
    Optional<ComplexTap> rf();

    /**
     * Returns post-carrier/downconversion samples.
     *
     * @return baseband tap if meaningful
     */
    Optional<ComplexTap> baseband();

    /**
     * Returns recovered complex symbols.
     *
     * @return symbol tap if available
     */
    Optional<ComplexTap> symbols();
}