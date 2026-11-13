package ru.boostyatv.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TimeFormatTest {
    @Test public void parsesMinutesAndSeconds() {
        assertEquals(150_000, TimeFormat.parse("2:30"));
        assertEquals(3_723_000, TimeFormat.parse("1:02:03"));
        assertEquals(0, TimeFormat.parse("0:00"));
    }

    @Test public void rejectsMalformedValues() {
        assertEquals(-1, TimeFormat.parse(""));
        assertEquals(-1, TimeFormat.parse("1:2:3:4"));
        assertEquals(-1, TimeFormat.parse("live"));
        assertEquals(-1, TimeFormat.parse("1:60"));
        assertEquals(-1, TimeFormat.parse("-1:00"));
        assertEquals(-1, TimeFormat.parse("1:2x"));
    }
}
