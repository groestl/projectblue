package org.pgjava.engine;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-database publish/subscribe bus for PostgreSQL {@code LISTEN}/{@code NOTIFY}.
 *
 * <p>A session calls {@link #subscribe} when it executes {@code LISTEN channel}.
 * When any session (or the bus directly) publishes a notification, every currently
 * subscribed listener receives a {@link PgNotification} synchronously on the
 * publishing thread.
 *
 * <p>Thread-safety: subscribe/unsubscribe/publish may be called from concurrent
 * sessions without external synchronisation.
 */
public final class NotificationBus {

    /** Callback interface implemented by sessions that have executed LISTEN. */
    @FunctionalInterface
    public interface Listener {
        void deliver(PgNotification notification);
    }

    /** channel → list of subscribed listeners. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>> subs =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------

    /**
     * Subscribe {@code listener} to {@code channel}.
     * Multiple subscriptions of the same listener to the same channel are allowed
     * but will result in duplicate deliveries — callers should guard against re-subscribing.
     */
    public void subscribe(String channel, Listener listener) {
        subs.computeIfAbsent(normalise(channel), k -> new CopyOnWriteArrayList<>())
            .add(listener);
    }

    /**
     * Unsubscribe {@code listener} from {@code channel}.
     * No-op if the listener is not subscribed to that channel.
     */
    public void unsubscribe(String channel, Listener listener) {
        List<Listener> list = subs.get(normalise(channel));
        if (list != null) list.remove(listener);
    }

    /**
     * Unsubscribe {@code listener} from every channel it is currently subscribed to.
     * Called on session close.
     */
    public void unsubscribeAll(Listener listener) {
        subs.values().forEach(list -> list.remove(listener));
    }

    /**
     * Publish a notification on {@code channel} from the sender identified by {@code senderPid}.
     * All currently subscribed listeners are called synchronously.
     */
    public void publish(String channel, String payload, int senderPid) {
        String norm = normalise(channel);
        PgNotification notif = new PgNotification(senderPid, norm, payload == null ? "" : payload);
        List<Listener> list = subs.get(norm);
        if (list != null) {
            for (Listener l : list) {
                try { l.deliver(notif); }
                catch (Exception ignored) { /* never let a bad listener kill the publisher */ }
            }
        }
    }

    // -------------------------------------------------------------------------

    /** PostgreSQL folds channel names to lower-case. */
    private static String normalise(String channel) {
        return channel == null ? "" : channel.toLowerCase();
    }
}
