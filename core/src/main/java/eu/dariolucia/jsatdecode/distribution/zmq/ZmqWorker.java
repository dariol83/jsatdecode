package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.OutputException;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/* Sole socket/context owner. Producers only reserve slots and publish immutable
   references. Lifecycle is management-serialized and producers quiesce before stop.
   No Thread.interrupt is sent to JeroMQ; cooperative shutdown drains local input. */
abstract class ZmqWorker<T> implements LifecycleComponent {
    final ZmqSettings settings;
    final SatDecodeWire wire;
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore slots;
    private final AtomicInteger submitting = new AtomicInteger();
    private final AtomicLong received = new AtomicLong(), sent = new AtomicLong(), dropped = new AtomicLong(), errors = new AtomicLong();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile String boundEndpoint;
    private volatile boolean bound;
    private Thread worker;
    private ZMQ.Socket socket;

    ZmqWorker(ZmqSettings settings) {
        this.settings = settings;
        slots = new Semaphore(settings.capacity());
        wire = new SatDecodeWire(settings, new UtcClock());
    }

    final void offer(T item) {
        Objects.requireNonNull(item);
        submitting.incrementAndGet();
        try {
            if (state != ComponentState.RUNNING) throw new OutputException("ZMQ output is not running", failure.get());
            received.incrementAndGet();
            boolean acquired = slots.tryAcquire();
            if (!acquired && settings.overflow() == OverflowPolicy.DROP_OLDEST) {
                T old = queue.poll();
                if (old != null) {
                    dropped.incrementAndGet();
                    slots.release();
                }
                acquired = slots.tryAcquire();
            }
            if (!acquired) {
                dropped.incrementAndGet();
                if (settings.overflow() == OverflowPolicy.FAIL_RUNTIME) {
                    var error = new OutputException("ZMQ queue overflow at " + settings.endpoint());
                    fail(error);
                    throw error;
                }
                return;
            }
            queue.add(item);
            LockSupport.unpark(worker);
        } finally {
            submitting.decrementAndGet();
        }
    }

    static long sequence(AtomicLong counter) {
        long value = counter.getAndIncrement();
        if (value < 0) throw new OutputException("ZMQ sequence exhausted");
        return value;
    }

    static long orderedSequence(long next, long submission) {
        if (next < 0 || submission < 0) throw new OutputException("ZMQ sequence exhausted");
        // Concurrent callers may reserve tickets in a different order from queue
        // publication. Preserve FIFO wire order without ever reversing sequence.
        // Serial producers retain exactly SatDecode's submission/drop gaps.
        return Math.max(next, submission);
    }

    @Override
    public final void start() {
        if (state != ComponentState.CONFIGURED) throw new IllegalStateException("ZMQ output is one-shot");
        state = ComponentState.STARTING;
        try {
            worker = new Thread(this::run, "jsatdecode-zmq-pub");
            worker.start();
        } catch (RuntimeException | Error e) {
            fail(e);
            throw e;
        }
        try {
            ready.await();
        } catch (InterruptedException e) {
            stop();
            Thread.currentThread().interrupt();
            throw new OutputException("Interrupted starting ZMQ output", e);
        }
        if (failure.get() != null) {
            stop();
            throw new OutputException("Cannot bind ZMQ output: " + settings.endpoint(), failure.get());
        }
    }

    private void run() {
        try (ZContext context = new ZContext(1)) {
            context.setLinger(settings.lingerMs());
            socket = context.createSocket(SocketType.PUB);
            socket.setSndHWM(settings.hwm());
            socket.setLinger(settings.lingerMs());
            if (settings.endpoint().contains("[")) socket.setIPv6(true);
            if (!socket.bind(settings.endpoint())) throw new OutputException("ZMQ bind failed: " + settings.endpoint());
            boundEndpoint = socket.getLastEndpoint();
            bound = true;
            if (state == ComponentState.STARTING) state = ComponentState.RUNNING;
            ready.countDown();
            opened();
            while (state == ComponentState.RUNNING || submitting.get() != 0 || !queue.isEmpty()) {
                T item = queue.poll();
                if (item != null) {
                    slots.release();
                    try {
                        process(item);
                    } catch (Throwable e) {
                        dropped.incrementAndGet();
                        throw e;
                    }
                }
                tick();
                if (item == null) LockSupport.parkNanos(1_000_000);
            }
            finished();
        } catch (Throwable e) {
            fail(e);
            while (submitting.get() != 0) LockSupport.parkNanos(100_000);
            while (queue.poll() != null) {
                slots.release();
                dropped.incrementAndGet();
            }
        } finally {
            bound = false;
            socket = null;
            ready.countDown();
        }
    }

    private void fail(Throwable e) {
        failure.compareAndSet(null, e);
        errors.incrementAndGet();
        state = ComponentState.FAILED;
    }

    final void send(List<byte[]> parts) {
        long length = 0;
        for (byte[] part : parts) length += part.length;
        if (length > settings.maxBytes()) throw new OutputException("Multipart message exceeds max_message_bytes");
        for (int i = 0; i < parts.size(); i++) {
            int flags = ZMQ.DONTWAIT | (i + 1 < parts.size() ? ZMQ.SNDMORE : 0);
            if (!socket.send(parts.get(i), flags))
                throw new OutputException("ZMQ multipart send failed: errno=" + socket.errno());
        }
        sent.incrementAndGet();
    }

    final void lost() {
        dropped.incrementAndGet();
    }

    void opened() {
    }

    abstract void process(T item);

    void tick() {
    }

    void finished() {
    }

    @Override
    public final void stop() {
        if (Thread.currentThread() == worker) throw new IllegalStateException("Cannot join ZMQ worker from itself");
        if (state == ComponentState.CLOSED || state == ComponentState.STOPPED) return;
        if (state != ComponentState.FAILED) state = ComponentState.STOPPING;
        LockSupport.unpark(worker);
        boolean interrupted = false;
        if (worker != null) while (worker.isAlive()) try {
            worker.join();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (state != ComponentState.FAILED) state = ComponentState.STOPPED;
    }

    @Override
    public final void close() {
        stop();
        state = ComponentState.CLOSED;
    }

    @Override
    public final ComponentState state() {
        return state;
    }

    /**
     * Returns immutable local counters; connected means bound, not subscriber acknowledgment.
     *
     * @return weakly consistent observation; sent means accepted by PUB
     */
    public final DistributionSnapshot monitoringSnapshot() {
        return new DistributionSnapshot(isRunning(), bound, received.get(), sent.get(), dropped.get(), errors.get(),
                settings.capacity() - slots.availablePermits(), settings.capacity(), eu.dariolucia.jsatdecode.util.UtcTime.nowNanos());
    }

    /**
     * Returns the actual bound TCP address, including an assigned wildcard port.
     *
     * @return empty before successful bind; retained after shutdown
     */
    public final Optional<String> endpoint() {
        return Optional.ofNullable(boundEndpoint);
    }

    /**
     * Returns the first local failure, retained after close.
     *
     * @return diagnostic or empty
     */
    public final Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }
}
