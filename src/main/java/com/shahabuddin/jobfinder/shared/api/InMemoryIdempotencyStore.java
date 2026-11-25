package com.shahabuddin.jobfinder.shared.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

/**
 * Keys in a bounded Caffeine cache: correct for <b>one instance</b>; keys are lost on restart (see
 * the ADR for the database-backed store a second instance requires).
 *
 * <ul>
 *   <li>Atomicity: {@code putIfAbsent}, {@code replace} and {@code remove} with the expected entry, on
 *       the cache's {@link ConcurrentMap} view.</li>
 *   <li>Lifetime: a key expires {@code ttl} after it was last written, i.e. after its response was
 *       stored.</li>
 *   <li>Memory: stored responses are weighed by their size and the least recently used are evicted
 *       beyond {@code maxStoredBytes}. Keys of requests still executing weigh nothing, so they are never
 *       evicted: that would let a concurrent duplicate through.</li>
 * </ul>
 */
final class InMemoryIdempotencyStore implements IdempotencyStore {

    /** Rough per-entry overhead (objects, map node, headers) on top of key and body. */
    private static final int ENTRY_OVERHEAD_BYTES = 256;

    private final Cache<String, Entry> cache;
    private final ConcurrentMap<String, Entry> entries;

    InMemoryIdempotencyStore(Duration ttl, long maxStoredBytes, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumWeight(maxStoredBytes)
                .weigher((String key, Entry entry) -> entry instanceof Done done
                        ? ENTRY_OVERHEAD_BYTES + key.length() + done.response().body().length
                        : 0)
                .ticker(ticker)
                .build();
        this.entries = cache.asMap();
    }

    @Override
    public Outcome begin(String key, String fingerprint) {
        Pending reservation = new Pending(key, fingerprint);
        Entry existing = entries.putIfAbsent(key, reservation);
        if (existing == null) {
            return new Outcome.Started(reservation);
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return new Outcome.KeyReused();
        }
        return existing instanceof Done done ? new Outcome.Replay(done.response()) : new Outcome.InProgress();
    }

    @Override
    public void complete(Reservation reservation, StoredResponse response) {
        Pending pending = (Pending) reservation;
        entries.replace(pending.key, pending, new Done(pending.fingerprint, response));
    }

    @Override
    public void release(Reservation reservation) {
        Pending pending = (Pending) reservation;
        entries.remove(pending.key, pending);
    }

    /**
     * Number of stored responses (for tests: memory must stay bounded). Runs the cache's pending
     * maintenance first: Caffeine evicts asynchronously, shortly after the writes that exceed the limit.
     */
    long storedResponses() {
        cache.cleanUp();
        return entries.values().stream().filter(Done.class::isInstance).count();
    }

    private sealed interface Entry permits Pending, Done {
        String fingerprint();
    }

    /** A request executing under the key. Compared by identity: only its owner may end it. */
    private static final class Pending implements Entry, Reservation {

        private final String key;
        private final String fingerprint;

        Pending(String key, String fingerprint) {
            this.key = key;
            this.fingerprint = fingerprint;
        }

        @Override
        public String fingerprint() {
            return fingerprint;
        }
    }

    private record Done(String fingerprint, StoredResponse response) implements Entry {
    }
}
