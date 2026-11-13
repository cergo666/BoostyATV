package ru.boostyatv.client;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class WatchHistoryTest {
    @Test public void orderKeepsNewestAndEvictsOldest() {
        WatchHistory.Order order = WatchHistory.nextOrder("a,b,c", "d", 3);
        assertEquals(List.of("b", "c", "d"), order.ids());
        assertEquals(List.of("a"), order.evicted());
    }

    @Test public void orderMovesExistingEntryToTheEnd() {
        WatchHistory.Order order = WatchHistory.nextOrder("a,b,c", "a", 3);
        assertEquals(List.of("b", "c", "a"), order.ids());
        assertTrue(order.evicted().isEmpty());
    }

    @Test public void orderStartsEmptyAndHasNoDuplicates() {
        WatchHistory.Order order = WatchHistory.nextOrder("", "a", 3);
        assertEquals(List.of("a"), order.ids());
        assertEquals(List.of("b", "a"), WatchHistory.nextOrder("a,b,a", "a", 3).ids());
    }

    @Test public void resumesFromTheSavedPosition() {
        WatchHistory.Progress progress = new WatchHistory.Progress(45_000, 120_000);
        assertEquals(45_000, progress.resume());
        assertEquals(37, progress.percentage());
    }

    @Test public void startsOverNearTheEnd() {
        assertEquals(0, new WatchHistory.Progress(119_500, 120_000).resume());
        assertEquals(0, new WatchHistory.Progress(120_000, 120_000).resume());
    }

    @Test public void percentageIsBounded() {
        assertEquals(0, new WatchHistory.Progress(0, 100_000).percentage());
        assertEquals(0, new WatchHistory.Progress(5_000, 0).percentage());
        assertEquals(1, new WatchHistory.Progress(1, 1_000_000).percentage());
        assertEquals(100, new WatchHistory.Progress(200_000, 100_000).percentage());
    }
}
