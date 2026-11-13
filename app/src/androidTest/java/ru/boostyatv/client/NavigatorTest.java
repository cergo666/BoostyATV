package ru.boostyatv.client;

import android.content.Context;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class NavigatorTest {
    @Test public void stackIsLifo() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Navigator navigator = new Navigator();
        View first = new View(context);
        View second = new View(context);
        navigator.push(first);
        navigator.push(second);
        assertSame(second, navigator.pop());
        assertSame(first, navigator.pop());
        assertTrue(navigator.isEmpty());
    }

    @Test public void depthIsCapped() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Navigator navigator = new Navigator();
        for (int i = 0; i < Navigator.LIMIT + 4; i++) {
            navigator.push(new View(context));
        }
        assertEquals(Navigator.LIMIT, navigator.size());
    }

    @Test public void clearEmptiesTheStack() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Navigator navigator = new Navigator();
        navigator.push(new View(context));
        navigator.clear();
        assertTrue(navigator.isEmpty());
    }
}
