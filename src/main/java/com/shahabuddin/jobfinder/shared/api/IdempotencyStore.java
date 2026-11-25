package com.shahabuddin.jobfinder.shared.api;

import java.util.Map;

/**
 * Where idempotency keys and their responses are kept (docs/adr/0014-idempotency-keys.md). The
 * contract every implementation must honour, whatever its storage:
 *
 * <ul>
 *   <li>{@link #begin} is <b>atomic</b>: of any number of concurrent requests with the same key,
 *       exactly one gets {@link Outcome.Started} (a check-then-act lookup let up to 3 of 16 identical
 *       concurrent requests through, measured).</li>
 *   <li>A key is bound to the fingerprint of its first request; a different request with it is
 *       {@link Outcome.KeyReused}.</li>
 *   <li>A reservation ends exactly once: {@link #complete} (the response is kept for the key's
 *       lifetime) or {@link #release} (the key is free again).</li>
 * </ul>
 */
interface IdempotencyStore {

    Outcome begin(String key, String fingerprint);

    void complete(Reservation reservation, StoredResponse response);

    void release(Reservation reservation);

    /** What to do with a request that carries a key. */
    sealed interface Outcome {

        /** First request with this key: execute it, then complete or release the reservation. */
        record Started(Reservation reservation) implements Outcome {
        }

        /** The same request completed before: answer with its response. */
        record Replay(StoredResponse response) implements Outcome {
        }

        /** The same request is still being executed. */
        record InProgress() implements Outcome {
        }

        /** The key belongs to a different request (method, path, query or body differ). */
        record KeyReused() implements Outcome {
        }
    }

    /** A claim on a key by the request executing it. */
    interface Reservation {
    }

    /** What is replayed: the status, the headers that describe the result, and the body as sent. */
    record StoredResponse(int status, Map<String, String> headers, byte[] body) {

        public StoredResponse {
            headers = Map.copyOf(headers);
        }
    }
}
