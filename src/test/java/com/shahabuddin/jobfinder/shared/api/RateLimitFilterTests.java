package com.shahabuddin.jobfinder.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The limiter's behaviour per client, without HTTP (all real-HTTP test requests share one address). */
class RateLimitFilterTests {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void eachClientHasItsOwnBucket() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(true, 2, Duration.ofMinutes(1), 100), json);
        assertThat(status(filter, "198.51.100.1")).isEqualTo(200);
        assertThat(status(filter, "198.51.100.1")).isEqualTo(200);
        assertThat(status(filter, "198.51.100.1")).isEqualTo(429);
        assertThat(status(filter, "198.51.100.2")).isEqualTo(200);
    }

    @Test
    void retryAfterIsTheTimeUntilTheNextToken() throws Exception {
        // 2 per minute: one token every 30 s.
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(true, 2, Duration.ofMinutes(1), 100), json);
        status(filter, "198.51.100.1");
        status(filter, "198.51.100.1");
        MockHttpServletResponse rejected = request(filter, "198.51.100.1");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(rejected.getHeader("Retry-After"))).isBetween(29, 30);
    }

    @Test
    void memoryStaysBoundedWhenClientsRotateAddresses() throws Exception {
        // A plain map would hold one bucket per address forever: an attacker cycling addresses
        // (IPv6 gives everyone billions) could exhaust the heap.
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(true, 5, Duration.ofMinutes(1), 100), json);
        for (int i = 0; i < 5_000; i++) {
            status(filter, "2001:db8::" + Integer.toHexString(i));
        }
        // Both bounds: "at most 100" alone also passed when the count was broken and always 0
        // (found by mutation testing).
        assertThat(filter.trackedClients()).isBetween(1L, 100L);
    }

    private static int status(RateLimitFilter filter, String address) throws Exception {
        return request(filter, address).getStatus();
    }

    private static MockHttpServletResponse request(RateLimitFilter filter, String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
