package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.api.IdempotencyStore.Outcome;
import com.shahabuddin.jobfinder.shared.api.IdempotencyStore.Reservation;
import com.shahabuddin.jobfinder.shared.api.IdempotencyStore.StoredResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@link IdempotencyStore} contract on the in-memory implementation (docs/adr/0014). */
class InMemoryIdempotencyStoreTests {

    private static final Duration TTL = Duration.ofHours(24);
    private static final StoredResponse CREATED = new StoredResponse(201, Map.of("Location", "/orders/1"), "{\"id\":1}".getBytes());

    private final AtomicLong nanos = new AtomicLong();
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(TTL, 1024 * 1024, nanos::get);

    @Test
    void aKeyIsExecutedOnceThenReplayed() {
        Reservation reservation = started(store.begin("key", "fp"));
        assertThat(store.begin("key", "fp")).isInstanceOf(Outcome.InProgress.class);

        store.complete(reservation, CREATED);

        assertThat(store.begin("key", "fp")).isEqualTo(new Outcome.Replay(CREATED));
    }

    @Test
    void aDifferentRequestWithTheKeyIsReusedWhetherRunningOrDone() {
        Reservation reservation = started(store.begin("key", "fp"));
        assertThat(store.begin("key", "other")).isInstanceOf(Outcome.KeyReused.class);

        store.complete(reservation, CREATED);
        assertThat(store.begin("key", "other")).isInstanceOf(Outcome.KeyReused.class);
    }

    @Test
    void aReleasedKeyIsFreeAgainEvenForADifferentRequest() {
        store.release(started(store.begin("key", "fp")));

        assertThat(store.begin("key", "other")).isInstanceOf(Outcome.Started.class);
    }

    @Test
    void onlyTheOwnerOfAReservationCanEndIt() {
        Reservation stale = started(store.begin("key", "fp"));
        store.release(stale);
        Reservation current = started(store.begin("key", "fp"));

        store.release(stale);                 // a late duplicate release
        store.complete(stale, CREATED);       // ...or completion: both must not touch the current owner's entry
        assertThat(store.begin("key", "fp")).isInstanceOf(Outcome.InProgress.class);

        store.complete(current, CREATED);
        assertThat(store.begin("key", "fp")).isInstanceOf(Outcome.Replay.class);
    }

    @Test
    void aCompletedKeyExpiresAfterItsTtl() {
        store.complete(started(store.begin("key", "fp")), CREATED);

        nanos.addAndGet(TTL.minusSeconds(1).toNanos());
        assertThat(store.begin("key", "fp")).isInstanceOf(Outcome.Replay.class);
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(store.begin("key", "fp")).isInstanceOf(Outcome.Started.class);
    }

    @Test
    void storedResponsesAreBoundedButRunningRequestsAreNeverEvicted() {
        InMemoryIdempotencyStore small = new InMemoryIdempotencyStore(TTL, 64 * 1024, nanos::get);
        Reservation running = started(small.begin("running", "fp"));
        StoredResponse large = new StoredResponse(200, Map.of(), new byte[8 * 1024]);
        for (int i = 0; i < 1000; i++) {   // ~8 MB of responses into 64 KB
            small.complete(started(small.begin("done-" + i, "fp")), large);
        }

        // Both bounds: "fewer than 10" alone also passed when the count was broken and always 0
        // (found by mutation testing).
        assertThat(small.storedResponses()).isBetween(1L, 9L);
        // Evicting it would let a concurrent duplicate of the running request execute.
        assertThat(small.begin("running", "fp")).isInstanceOf(Outcome.InProgress.class);
        small.release(running);
    }

    @Test
    void ofManyConcurrentRequestsWithTheSameKeyExactlyOneStarts() throws Exception {
        // A check-then-act lookup let up to 3 of 16 through (learning/39).
        int threads = 16;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int round = 0; round < 200; round++) {
                String key = UUID.randomUUID().toString();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Outcome>> outcomes = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    outcomes.add(pool.submit(() -> {
                        go.await();
                        return store.begin(key, "fp");
                    }));
                }
                go.countDown();
                int started = 0;
                for (Future<Outcome> outcome : outcomes) {
                    started += outcome.get() instanceof Outcome.Started ? 1 : 0;
                }
                assertThat(started).as("round %d", round).isOne();
            }
        }
    }

    private static Reservation started(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Started.class);
        return ((Outcome.Started) outcome).reservation();
    }
}
