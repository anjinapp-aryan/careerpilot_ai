package ai.careerpilot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8.1 — verifies RateLimitFilter enforces the previously-unenforced
 * security.rate-limit.* config, is a no-op when disabled, and exempts diagnostics/actuator.
 */
class RateLimitFilterTest {

    @Test
    void disabledIsANoOpRegardlessOfRequestVolume() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(false, 1, 1);
        HttpServletRequest req = request("/api/auth/login");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(req, res, chain);
        }

        verify(chain, times(5)).doFilter(req, res);
        verify(res, never()).setStatus(429);
    }

    @Test
    void blocksAfterAuthLimitExceededForSameIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 2, 100);
        HttpServletRequest req = request("/api/auth/login");
        HttpServletResponse res = mockResponseWithWriter();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);
        filter.doFilterInternal(req, res, chain);
        filter.doFilterInternal(req, res, chain);

        verify(chain, times(2)).doFilter(req, res);
        verify(res, times(1)).setStatus(429);
    }

    @Test
    void authAndGeneralApiLimitsAreIndependentBuckets() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 100);
        HttpServletRequest authReq = request("/api/auth/login");
        HttpServletRequest apiReq = request("/api/jobs");
        HttpServletResponse res = mockResponseWithWriter();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(authReq, res, chain);
        filter.doFilterInternal(apiReq, res, chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(res));
        verify(res, never()).setStatus(429);
    }

    @Test
    void diagnosticsAndActuatorAreExemptEvenWhenEnabled() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 1);
        HttpServletRequest req = request("/api/diagnostics/ai");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(req, res, chain);
        }

        verify(chain, times(10)).doFilter(req, res);
        verify(res, never()).setStatus(429);
    }

    @Test
    void differentIpsGetIndependentBuckets() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 100);
        HttpServletRequest reqA = request("/api/auth/login");
        when(reqA.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1");
        HttpServletRequest reqB = request("/api/auth/login");
        when(reqB.getHeader("X-Forwarded-For")).thenReturn("2.2.2.2");
        HttpServletResponse res = mockResponseWithWriter();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(reqA, res, chain);
        filter.doFilterInternal(reqB, res, chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(res));
        verify(res, never()).setStatus(429);
    }

    private static HttpServletRequest request(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return req;
    }

    private static HttpServletResponse mockResponseWithWriter() throws Exception {
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return res;
    }
}
