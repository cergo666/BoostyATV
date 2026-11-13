package ru.boostyatv.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ReactionsTest {
    @Test public void parsesAggregatesCountersAndActorAlias() throws Exception {
        Reactions reactions = Reactions.parse(new JSONObject("""
            {"reactions":{"like":2,"laught":3},
             "reacted":{"actor":"laught"},
             "reactionCounters":[{"type":"fire","count":5}]}
            """));
        assertEquals("laugh", reactions.selected());
        assertEquals(2, reactions.count(Reactions.Type.LIKE));
        assertEquals(3, reactions.count(Reactions.Type.LAUGH));
        assertEquals(5, reactions.count(Reactions.Type.FIRE));
        assertEquals(0, reactions.count(Reactions.Type.SAD));
    }

    @Test public void changingReactionMovesCounts() throws Exception {
        Reactions initial = new Reactions("like", new java.util.LinkedHashMap<>(
            java.util.Map.of("like", 1, "fire", 2)));
        Reactions changed = initial.withSelected("fire");
        assertEquals("fire", changed.selected());
        assertEquals(0, changed.count(Reactions.Type.LIKE));
        assertEquals(3, changed.count(Reactions.Type.FIRE));
        assertSame(initial, initial.withSelected("like"));
        Reactions removed = changed.withSelected("");
        assertEquals("", removed.selected());
        assertEquals(2, removed.count(Reactions.Type.FIRE));
    }

    @Test public void allowedReactionsRespectChannelSettings() throws Exception {
        assertTrue(Reactions.allowed(new JSONObject().put("reactionsDisabled", true)).isEmpty());
        assertEquals(8, Reactions.allowed(new JSONObject()).size());
        var selected = Reactions.allowed(new JSONObject().put("allowedReactionList",
            new JSONArray().put("fire").put("like").put("unknown").put("fire")));
        assertEquals(2, selected.size());
        assertEquals(Reactions.Type.FIRE, selected.get(0));
        assertEquals(Reactions.Type.LIKE, selected.get(1));
    }
}
