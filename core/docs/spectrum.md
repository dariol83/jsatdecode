# FFT and spectrum utilities

The monitoring window uses these utilities for RF/baseband plots. See [spectrum-ui.md](spectrum-ui.md)
for worker ownership, complete-snapshot policy, immutable column reduction,
relative-dB axes, configuration and integration validation. The utility
normalization and ownership conventions are described below.

Java-SE-only numerical utilities reside in eu.dariolucia.jsatdecode.dsp.fft.
They create no workers, UI objects or callbacks and do no I/O.
Observer FFT processing runs on an analysis worker, never the decoder thread or a paint method.

## Plans, ownership and normalization

ComplexFft(size) precomputes bit reversal and double-precision twiddles.
Size must be a positive power of two; N=1 is an identity transform.
Plans use O(N) memory and O(N log N) processing.
The forward transform is X[k] = sum(x[n] exp(-j 2 pi k n/N)), without normalization.
The inverse uses a positive exponent and divides by N.
A round trip recovers the original complex samples within floating-point error.

The plan API uses instances rather than per-call static setup: construct once and reuse.
There is no global cache, implicit scratch array or size change during processing.
Plans are immutable; sharing them concurrently is safe with disjoint input/work arrays.
Real and imaginary buffers must be non-null, distinct and exactly the plan size.
Calls mutate those buffers in place, retain neither, allocate nothing and do not block.
IEEE nonfinite sample values may propagate through the FFT; the transform does not sanitize samples.

The FFT uses double work arrays.
Convert float IQ snapshots into reusable real/imaginary work buffers on the observer thread.
An empty tap snapshot is normal: skip that analysis cycle. The monitoring window
also skips partial snapshots without zero padding; the FFT utility requires
complete arrays of the plan size.

## Windows

WindowFunction supports RECTANGULAR, HANN, HAMMING and BLACKMAN.
Window(size) defaults to Hann; Window(function,size) selects explicitly.
Coefficients are symmetric, with theta = 2 pi n/(N-1):

| Function | Coefficient |
| --- | --- |
| RECTANGULAR | 1 |
| HANN | 0.5 - 0.5 cos(theta) |
| HAMMING | 0.54 - 0.46 cos(theta) |
| BLACKMAN | 0.42 - 0.5 cos(theta) + 0.08 cos(2 theta) |

N must be positive but need not be a power of two. Every N=1 window is defined as one.
The symmetric two-point Hann has zero gain.
Tiny endpoint rounding errors are preserved rather than clamped.
Window.apply multiplies both distinct length-N arrays in place using cached coefficients.
No amplitude correction is applied. coherentGain() is the coefficient mean; for a bin-centered
complex tone, dividing FFT magnitude by N*coherentGain corrects its peak amplitude when gain is nonzero.
This is not a noise-power, bandwidth, dBm or PSD calibration.

## Spectrum conversions

Spectrum.magnitude computes hypot(real,imaginary); Spectrum.power computes real squared plus imaginary squared.
Both accept output aliasing either input and otherwise leave input arrays unchanged.
Magnitude avoids intermediate square overflow; power follows IEEE overflow/underflow.
A NaN component produces unavailable NaN output.

powerToDb uses 10 log10(power); magnitudeToDb uses 20 log10(magnitude).
Both clamp to an explicit finite floor, map zero to that floor, preserve NaN and positive infinity,
and reject negative input before modifying output. Destination may alias input.
The logarithm is taken directly, avoiding an underflow-prone conversion of very low floors to linear power.
Lengths must match; empty conversion arrays are allowed.

These results are relative dB referenced to one. FFT size, window gain, hardware level and bandwidth are not normalized automatically.
Do not label them as calibrated dBm or PSD.

## Ordering and frequency axes

fftShift rotates left by ceil(N/2), moving DC to floor(N/2).
It supports separate output or in-place operation, including odd, even, empty and singleton arrays.
In-place shifts use three reversals rather than a temporary array.
For example [0,1,2,3,4] becomes [3,4,0,1,2].

frequencyAtBin(index,N,Fs,Fc) returns Fc + (index-floor(N/2))*Fs/N.
For even N this spans [Fc-Fs/2,Fc+Fs/2), and the Nyquist bin is displayed at the negative edge.
For odd N it gives the centered discrete bin frequencies.
Fs must be positive/finite, Fc finite, and the index valid. Extreme finite Fc/Fs may overflow the resulting sum.
No axis array is allocated.

## Averaging and screen reduction

ExponentialAverager(N,alpha) maintains fixed state; 0 < alpha <= 1 and alpha must be finite.
The first available sample seeds each bin, then the recurrence is alpha*current + (1-alpha)*previous.
Alpha=1 disables smoothing. NaN skips that bin's update, retaining the previous estimate or NaN if never seeded.
Infinity is rejected before changing any state. reset() clears all bins to unavailable NaN.
The caller chooses the domain: smoothing power differs from smoothing dB.
State is thread-confined and never retains supplied arrays. update may overwrite the current array in place.
copyTo copies state into caller storage. Neither repeated operation allocates.

reduceMaximum maps N input bins to W contiguous, nonempty pixel columns with boundaries floor(c*N/W).
Require 1 <= W <= N, or both arrays empty. Upsampling and zero width with nonempty input are rejected.
Every bin contributes to exactly one column, retaining narrow peaks at uneven boundaries.
NaNs are ignored if the column has a valid value; an all-NaN column stays NaN.
No display object or per-bin object is created.

## Reusable analysis example

Allocate once per analysis stream (imports omitted):

~~~java
int size = 4096;
ComplexFft fft = new ComplexFft(size);
Window window = new Window(size); // symmetric Hann
ExponentialAverager smoother = new ExponentialAverager(size, 0.2);
double[] real = new double[size], imaginary = new double[size];
double[] power = new double[size], shifted = new double[size];
double[] averaged = new double[size], db = new double[size];
double[] columns = new double[1000];
~~~

For each complete length-size observation, fill real/imaginary from the snapshot, then reuse:

~~~java
window.apply(real, imaginary);
fft.forward(real, imaginary);
Spectrum.power(real, imaginary, power);
Spectrum.fftShift(power, shifted);
smoother.update(shifted, averaged); // linear power averaging
Spectrum.powerToDb(averaged, db, -160);
Spectrum.reduceMaximum(db, columns);
~~~

If observations can contain infinite samples, reject/sanitize that cycle before averaging;
NaN bins are treated as unavailable as documented above.
The arrays stay private to the analysis worker. A UI consumer must receive an immutable
view model or lifetime-isolated copy rather than buffers that the worker will rewrite.
The numerical utilities have no Swing dependency.
