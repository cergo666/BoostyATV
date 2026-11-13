package ru.boostyatv.client;

import android.view.View;

import java.util.ArrayDeque;

/** Back-stack for programmatic screens with a bounded depth. */
final class Navigator {
    static final int LIMIT = 10;

    private final ArrayDeque<View> stack = new ArrayDeque<>();

    /** Remember the current screen before navigating away. */
    void push(View current) {
        current.setTag(current.findFocus());
        stack.push(current);
        while (stack.size() > LIMIT) stack.removeLast();
    }

    View pop() {
        return stack.pop();
    }

    boolean isEmpty() {
        return stack.isEmpty();
    }

    void clear() {
        stack.clear();
    }

    int size() {
        return stack.size();
    }
}
