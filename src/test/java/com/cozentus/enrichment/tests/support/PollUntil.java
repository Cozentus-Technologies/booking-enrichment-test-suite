package com.cozentus.enrichment.tests.support;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Every wait in the suite is a bounded poll. {@code Thread.sleep} as a substitute
 * for waiting on a condition appears nowhere: it is either too short and flaky or
 * too long and slow, and it hides which of the two it is.
 *
 * <p>The short interval below is a yield between attempts, not a fixed wait — the
 * loop exits the moment the condition holds.
 */
public final class PollUntil {

    private static final Duration INTERVAL = Duration.ofMillis(50);

    private PollUntil() {
    }

    /**
     * Polls until the supplier yields a value.
     *
     * @return the value, or empty if the timeout elapsed first
     */
    public static <T> Optional<T> present(Supplier<Optional<T>> attempt, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<T> result = attempt.get();
            if (result.isPresent()) {
                return result;
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            pause();
        }
    }

    /** @return true if the condition became true within the timeout */
    public static boolean isTrue(BooleanSupplier condition, Duration timeout) {
        return present(() -> condition.getAsBoolean() ? Optional.of(true) : Optional.<Boolean>empty(),
                timeout).isPresent();
    }

    /**
     * Watches for the whole window and reports whether the condition ever became
     * true. Unlike {@link #isTrue} this does not exit early on success — an
     * absence claim is only as good as the time spent looking.
     *
     * @return true if the condition held at any point during the window
     */
    public static boolean everTrue(BooleanSupplier condition, Duration window) {
        long deadline = System.nanoTime() + window.toNanos();
        boolean seen = false;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                seen = true;
                break;
            }
            pause();
        }
        return seen;
    }

    private static void pause() {
        try {
            Thread.sleep(INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling", e);
        }
    }
}
