package org.pgjava.engine;

/**
 * A single asynchronous notification, as produced by {@code NOTIFY} and
 * consumed by sessions that have executed {@code LISTEN}.
 *
 * <p>Mirrors {@code org.postgresql.PGNotification} semantics:
 * <ul>
 *   <li>{@code pid} — backend PID of the session that sent the notification
 *       (we use {@link System#identityHashCode(Object)} of the sending session
 *       as a stable per-session pseudo-PID).
 *   <li>{@code channel} — the channel name (case-folded to lower-case, as PG does).
 *   <li>{@code payload} — the optional payload string (empty string if omitted).
 * </ul>
 */
public record PgNotification(int pid, String channel, String payload) {

    /** Notification with an empty payload. */
    public static PgNotification of(int pid, String channel) {
        return new PgNotification(pid, channel, "");
    }
}
