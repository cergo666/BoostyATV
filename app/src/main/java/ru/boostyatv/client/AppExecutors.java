package ru.boostyatv.client;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared thread configuration: named daemon threads that log uncaught failures. */
final class AppExecutors {
    private AppExecutors() {}

    static ExecutorService network(String name, int threads) {
        return Executors.newFixedThreadPool(threads, named(name));
    }

    static Thread thread(String name, Runnable task) {
        ThreadFactory factory = named(name);
        return factory.newThread(task);
    }

    private static ThreadFactory named(String name) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread worker = new Thread(task, "BoostyATV-" + name + "-" + sequence.incrementAndGet());
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((thread, error) ->
                Log.w("BoostyATV", "Background task failed on " + thread.getName(), error));
            return worker;
        };
    }
}
