package ru.boostyatv.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WebSessionTest {
    private static final String DEVICE = "0f8e6a2b-1c3d-4e5f-6a7b-8c9d0e1f2a3b";

    private static WebSession parse(String expiresField) throws Exception {
        return WebSession.parse("{\"accessToken\":\"a\",\"refreshToken\":\"b\"," + expiresField + "}", DEVICE);
    }

    @Test public void acceptsIsoTimestamps() throws Exception {
        assertEquals(1893456000L, parse("\"expiresAt\":\"2030-01-01T00:00:00Z\"").expiry());
        assertEquals(1893456000L, parse("\"expires_at\":\"2030-01-01T03:00:00+03:00\"").expiry());
    }

    @Test public void addsRelativeSecondsToNow() throws Exception {
        long expiry = parse("\"expires_in\":\"3600\"").expiry();
        long now = System.currentTimeMillis() / 1000L;
        assertTrue(expiry > now + 3540 && expiry <= now + 3600);
    }

    @Test public void keepsAbsoluteEpochAndMilliseconds() throws Exception {
        assertEquals(1893456000L, parse("\"expiresAt\":\"1893456000\"").expiry());
        assertEquals(1893456000L, parse("\"expiresAt\":\"1893456000000\"").expiry());
    }

    @Test public void unknownFormatsStayUnknown() throws Exception {
        assertEquals(0, parse("\"expiresAt\":\"tomorrow\"").expiry());
        assertEquals(0, parse("\"expiresAt\":\"\"").expiry());
    }
}
