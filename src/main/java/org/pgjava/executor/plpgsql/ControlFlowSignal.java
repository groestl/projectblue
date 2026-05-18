package org.pgjava.executor.plpgsql;

/**
 * Unchecked exceptions used purely as interpreter control flow signals.
 * These are never exposed to the user — they're caught at the appropriate
 * PL/pgSQL construct boundary (function for RETURN, loop for EXIT/CONTINUE).
 */
public abstract sealed class ControlFlowSignal extends RuntimeException {

    ControlFlowSignal() { super(null, null, true, false); } // no stack trace

    /** Thrown by RETURN [expr] — caught at function boundary. */
    public static final class ReturnSignal extends ControlFlowSignal {
        private final Object value;

        public ReturnSignal(Object value) { this.value = value; }

        public Object value() { return value; }
    }

    /** Thrown by EXIT [label] [WHEN cond] — caught by matching LOOP. */
    public static final class ExitSignal extends ControlFlowSignal {
        private final String label;

        public ExitSignal(String label) { this.label = label; }

        public String label() { return label; }
    }

    /** Thrown by CONTINUE [label] [WHEN cond] — caught by matching LOOP. */
    public static final class ContinueSignal extends ControlFlowSignal {
        private final String label;

        public ContinueSignal(String label) { this.label = label; }

        public String label() { return label; }
    }
}
