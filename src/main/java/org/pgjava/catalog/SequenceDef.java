package org.pgjava.catalog;

import org.pgjava.engine.PgErrorException;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

/** A named sequence (CREATE SEQUENCE / SERIAL). */
public final class SequenceDef {

    private final long   oid;
    private final String name;
    private final String schemaName;
    private final long   start;
    private final long   increment;
    private final long   minVal;
    private final long   maxVal;
    private final boolean cycle;

    private final AtomicLong current;
    // Per-session (per-thread) tracking of the last value returned by nextval.
    // volatile Long on the shared SequenceDef leaks across sessions; ThreadLocal isolates correctly.
    private final ThreadLocal<Long> lastValue = new ThreadLocal<>();

    public SequenceDef(long oid, String name, String schemaName,
                       long start, long increment, long minVal, long maxVal, boolean cycle) {
        this.oid        = oid;
        this.name       = name;
        this.schemaName = schemaName;
        this.start      = start;
        this.increment  = increment;
        this.minVal     = minVal;
        this.maxVal     = maxVal;
        this.cycle      = cycle;
        this.current    = new AtomicLong(start - increment); // nextval advances before returning
    }

    /** Default sequence: start=1, increment=1, no cycle, no bounds. */
    public static SequenceDef defaultSeq(long oid, String name, String schema) {
        return new SequenceDef(oid, name, schema, 1, 1, 1, Long.MAX_VALUE, false);
    }

    // -------------------------------------------------------------------------

    public long   oid()        { return oid; }
    public String name()       { return name; }
    public String schemaName() { return schemaName; }
    public long   start()      { return start; }
    public long   increment()  { return increment; }
    public long   minVal()     { return minVal; }
    public long   maxVal()     { return maxVal; }
    public boolean cycle()     { return cycle; }
    public long   current()    { return current.get(); }

    /**
     * Advance and return the next value.
     *
     * @throws SQLException SQLSTATE 2200H if the sequence has reached its limit and cycle=false
     */
    public long nextval() throws SQLException {
        long next = current.addAndGet(increment);
        if (increment > 0 && next > maxVal) {
            if (!cycle) throw PgErrorException.error("2200H",
                    "nextval: reached maximum value of sequence \"" + name + "\" (" + maxVal + ")").build();
            // Wrap to effective minimum (default 1 for ascending sequences)
            long wrapTo = (minVal == Long.MIN_VALUE) ? 1L : minVal;
            current.set(wrapTo);
            next = wrapTo;
        } else if (increment < 0 && next < minVal) {
            if (!cycle) throw PgErrorException.error("2200H",
                    "nextval: reached minimum value of sequence \"" + name + "\" (" + minVal + ")").build();
            // Wrap to effective maximum (default -1 for descending sequences)
            long wrapTo = (maxVal == Long.MAX_VALUE) ? -1L : maxVal;
            current.set(wrapTo);
            next = wrapTo;
        }
        lastValue.set(next);
        return next;
    }

    /**
     * Return the current value without advancing.
     *
     * @throws SQLException SQLSTATE 55000 if nextval has not been called yet in this session
     */
    public long currval() throws SQLException {
        Long val = lastValue.get();
        if (val == null)
            throw PgErrorException.error("55000",
                    "currval of sequence \"" + name + "\" is not yet defined in this session").build();
        return val;
    }

    /** Set the sequence's current value (is_called=true: currval/lastval return this value). */
    public void setval(long value) {
        current.set(value);
        lastValue.set(value);
    }

    /**
     * Set the sequence's next value without marking it "called"
     * (is_called=false: next nextval() returns this value; currval/lastval unchanged).
     */
    public void setvalNotCalled(long value) {
        current.set(value - increment);
        lastValue.remove();
    }

    /** Reset the sequence to its start value (for TRUNCATE ... RESTART IDENTITY). */
    public void restart() {
        current.set(start - increment);
        lastValue.remove();
    }
}
