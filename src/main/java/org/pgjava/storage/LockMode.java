package org.pgjava.storage;

/**
 * PostgreSQL table-level lock modes.
 *
 * <p>Eight modes ordered from weakest to strongest, with a static
 * {@link #conflicts(LockMode, LockMode)} method encoding PostgreSQL's exact
 * compatibility matrix (see PostgreSQL docs, "Table 13.2. Conflicting Lock Modes").
 *
 * <p>Two locks conflict if and only if they cannot be held simultaneously on the
 * same table by different transactions.  A transaction never conflicts with itself.
 */
public enum LockMode {

    /** Acquired by {@code SELECT}. */
    ACCESS_SHARE,

    /** Acquired by {@code SELECT FOR UPDATE / FOR SHARE}. */
    ROW_SHARE,

    /** Acquired by {@code INSERT}, {@code UPDATE}, {@code DELETE}. */
    ROW_EXCLUSIVE,

    /** Acquired by {@code VACUUM} (non-FULL), {@code CREATE INDEX CONCURRENTLY}. */
    SHARE_UPDATE_EXCLUSIVE,

    /** Acquired by {@code CREATE INDEX} (non-concurrent). */
    SHARE,

    /** Acquired by {@code CREATE TRIGGER} and some {@code ALTER TABLE} forms. */
    SHARE_ROW_EXCLUSIVE,

    /** Acquired by explicit {@code LOCK TABLE ... IN EXCLUSIVE MODE}. */
    EXCLUSIVE,

    /** Acquired by {@code ALTER TABLE}, {@code DROP TABLE}, {@code VACUUM FULL},
     *  {@code LOCK TABLE} (default mode). */
    ACCESS_EXCLUSIVE;

    /*
     * Conflict matrix — indexed by [requested][held].
     * true = the two modes CONFLICT (cannot coexist on the same relation).
     *
     * Source: PostgreSQL 15 documentation, Table 13.2.
     *
     * Row = requested mode, Column = existing held mode.
     *           AS   RS   RE   SUE  S    SRE  E    AE
     * AS        -    -    -    -    -    -    -    X
     * RS        -    -    -    -    -    -    X    X
     * RE        -    -    -    -    X    X    X    X
     * SUE       -    -    -    X    X    X    X    X
     * S         -    -    X    X    -    X    X    X
     * SRE       -    -    X    X    X    X    X    X
     * E         -    X    X    X    X    X    X    X
     * AE        X    X    X    X    X    X    X    X
     */
    private static final boolean[][] CONFLICTS = {
        //              AS     RS     RE     SUE    S      SRE    E      AE
        /* AS  */   { false, false, false, false, false, false, false, true  },
        /* RS  */   { false, false, false, false, false, false, true,  true  },
        /* RE  */   { false, false, false, false, true,  true,  true,  true  },
        /* SUE */   { false, false, false, true,  true,  true,  true,  true  },
        /* S   */   { false, false, true,  true,  false, true,  true,  true  },
        /* SRE */   { false, false, true,  true,  true,  true,  true,  true  },
        /* E   */   { false, true,  true,  true,  true,  true,  true,  true  },
        /* AE  */   { true,  true,  true,  true,  true,  true,  true,  true  },
    };

    /**
     * Returns {@code true} if lock modes {@code a} and {@code b} conflict —
     * i.e. they cannot be held simultaneously on the same relation by different
     * transactions.
     */
    public static boolean conflicts(LockMode a, LockMode b) {
        return CONFLICTS[a.ordinal()][b.ordinal()];
    }

    /**
     * Returns {@code true} if {@code this} mode is at least as strong as {@code other}.
     * Used for lock upgrades: if a transaction already holds a stronger-or-equal lock,
     * the weaker request is a no-op.
     */
    public boolean isAtLeastAsStrong(LockMode other) {
        return this.ordinal() >= other.ordinal();
    }

    /**
     * Returns the PostgreSQL lock mode name as used in {@code pg_locks.mode},
     * e.g. {@code "AccessShareLock"}.
     */
    public String pgName() {
        return switch (this) {
            case ACCESS_SHARE           -> "AccessShareLock";
            case ROW_SHARE              -> "RowShareLock";
            case ROW_EXCLUSIVE          -> "RowExclusiveLock";
            case SHARE_UPDATE_EXCLUSIVE -> "ShareUpdateExclusiveLock";
            case SHARE                  -> "ShareLock";
            case SHARE_ROW_EXCLUSIVE    -> "ShareRowExclusiveLock";
            case EXCLUSIVE              -> "ExclusiveLock";
            case ACCESS_EXCLUSIVE       -> "AccessExclusiveLock";
        };
    }

    /**
     * Parse the integer lock mode from PostgreSQL's {@code LockStmt.mode} field.
     * Values 1–8 correspond to AccessShare through AccessExclusive.
     */
    public static LockMode fromPgMode(int mode) {
        return switch (mode) {
            case 1 -> ACCESS_SHARE;
            case 2 -> ROW_SHARE;
            case 3 -> ROW_EXCLUSIVE;
            case 4 -> SHARE_UPDATE_EXCLUSIVE;
            case 5 -> SHARE;
            case 6 -> SHARE_ROW_EXCLUSIVE;
            case 7 -> EXCLUSIVE;
            case 8 -> ACCESS_EXCLUSIVE;
            default -> throw new IllegalArgumentException("Invalid PG lock mode: " + mode);
        };
    }
}
