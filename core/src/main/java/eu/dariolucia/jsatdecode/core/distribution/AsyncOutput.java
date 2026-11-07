package eu.dariolucia.jsatdecode.core.distribution;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.OutputException;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/* Shared bounded mailbox. Lifecycle is serialized by the owner; producers must
   be quiescent before stop. A permit covers queued items and pending publication.
   Transport is called only on the owned worker, never under a producer lock. */
final class AsyncOutput<T> implements LifecycleComponent {
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore slots;
    private final int capacity;
    private final OverflowPolicy policy;
    private final Consumer<T> output;
    private final AtomicLong received = new AtomicLong(), sent = new AtomicLong(), dropped = new AtomicLong(), errors = new AtomicLong();
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private volatile Throwable failure;
    private Thread worker;
    private final AtomicInteger submitting = new AtomicInteger();

    AsyncOutput(int capacity, OverflowPolicy policy, Consumer<T> output) {
        if (capacity <= 0) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
        slots = new Semaphore(capacity);
        this.policy = Objects.requireNonNull(policy);
        this.output = Objects.requireNonNull(output);
    }

    void offer(T item) {
        submitting.incrementAndGet();
        try {
            offerInternal(item);
        } finally {
            submitting.decrementAndGet();
        }
    }

    private void offerInternal(T item) {
        Objects.requireNonNull(item);
        if (state != ComponentState.RUNNING) throw new OutputException("Output is not running", failure);
        received.incrementAndGet();
        if (!slots.tryAcquire()) {
            if (policy == OverflowPolicy.DROP_OLDEST) {
                T old = queue.poll();
                if (old != null) {
                    dropped.incrementAndGet();
                    slots.release();
                }
                if (slots.tryAcquire()) {
                    publish(item);
                    return;
                }
            }
            dropped.incrementAndGet();
            if (policy == OverflowPolicy.FAIL_RUNTIME) {
                OutputException overflow = new OutputException("Output queue overflow");
                failure = overflow;
                errors.incrementAndGet();
                state = ComponentState.FAILED;
                throw overflow;
            }
            return;
        }
        publish(item);
    }

    private void publish(T item) {
        queue.add(item);
        LockSupport.unpark(worker);
    }

    @Override
    public void start() {
        if (state != ComponentState.CONFIGURED) throw new IllegalStateException("Output is one-shot");
        state = ComponentState.STARTING;
        try {
            worker = new Thread(this::run, "jsatdecode-output");
            state = ComponentState.RUNNING;
            worker.start();
        } catch (RuntimeException | Error e) {
            failure = e;
            state = ComponentState.FAILED;
            throw e;
        }
    }

    private void run() {
        try {
            while (state == ComponentState.RUNNING || submitting.get() != 0 || !queue.isEmpty()) {
                T item = queue.poll();
                if (item == null) {
                    LockSupport.parkNanos(1_000_000);
                    continue;
                }
                slots.release();
                try {
                    output.accept(item);
                    sent.incrementAndGet();
                } catch (Throwable e) {
                    dropped.incrementAndGet();
                    throw e;
                }
            }
        } catch (Throwable e) {
            failure = e;
            errors.incrementAndGet();
            state = ComponentState.FAILED;
            while (submitting.get() != 0) LockSupport.parkNanos(100_000);
            while (queue.poll() != null) {
                slots.release();
                dropped.incrementAndGet();
            }
        }
    }

    @Override
    public void stop() {
        if (Thread.currentThread() == worker) throw new IllegalStateException("Cannot join output callback");
        if (state == ComponentState.CLOSED || state == ComponentState.STOPPED) return;
        boolean failed = state == ComponentState.FAILED;
        if (!failed) state = ComponentState.STOPPING;
        LockSupport.unpark(worker);
        boolean interrupted = false;
        if (worker != null) while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (state != ComponentState.FAILED) state = ComponentState.STOPPED;
    }

    @Override
    public void close() {
        stop();
        state = ComponentState.CLOSED;
    }

    @Override
    public ComponentState state() {
        return state;
    }

    DistributionSnapshot snapshot() {
        return new DistributionSnapshot(isRunning(), isRunning(), received.get(), sent.get(), dropped.get(), errors.get(),
                Math.min(capacity, capacity - slots.availablePermits()), capacity, eu.dariolucia.jsatdecode.util.UtcTime.nowNanos());
    }
}

