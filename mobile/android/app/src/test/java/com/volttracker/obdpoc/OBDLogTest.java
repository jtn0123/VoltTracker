package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pinning tests for {@link OBDLog#format(Object)} — null/empty/plain/quoted handling. */
public class OBDLogTest {

    @Test
    public void formatRendersNullAsDash() {
        assertEquals("-", OBDLog.format(null));
    }

    @Test
    public void formatRendersEmptyAsQuotedEmpty() {
        assertEquals("\"\"", OBDLog.format(""));
    }

    @Test
    public void formatRendersPlainValueUnquoted() {
        assertEquals("Adapter1", OBDLog.format("Adapter1"));
        assertEquals("42", OBDLog.format(42));
        assertEquals("true", OBDLog.format(true));
    }

    @Test
    public void formatQuotesValueWithSpace() {
        assertEquals("\"OBD adapter\"", OBDLog.format("OBD adapter"));
    }

    @Test
    public void formatQuotesValueWithEquals() {
        assertEquals("\"k=v\"", OBDLog.format("k=v"));
    }

    @Test
    public void formatEscapesEmbeddedQuotes() {
        assertEquals("\"he said \\\"hi\\\"\"", OBDLog.format("he said \"hi\""));
    }

    @Test
    public void formatEscapesNewlineAndCarriageReturn() {
        // Multi-line values would otherwise split a single log entry across lines and break the
        // grep-one-line-per-event contract. After escape, a value with no space/equals/quote stays
        // unquoted — still single-line, still grep-safe.
        assertEquals("line1\\nline2", OBDLog.format("line1\nline2"));
        assertEquals("a\\rb", OBDLog.format("a\rb"));
        assertEquals("a\\r\\nb", OBDLog.format("a\r\nb"));
        // Composes with the quoting path when a space is present.
        assertEquals("\"a b\\nc\"", OBDLog.format("a b\nc"));
    }

    @Test
    public void formatQuotesValueWithEmbeddedDoubleQuote() {
        // No space, no equals, but contains a quote → must still be quoted so the value reads
        // unambiguously.
        assertEquals("\"\\\"\"", OBDLog.format("\""));
    }

    @Test
    public void formatEscapesPreExistingBackslashesBeforeOurOwnEscapes() {
        // Source backslashes get doubled so the \n / \r escapes we introduce aren't ambiguous
        // with a literal \n in the input.
        assertEquals("a\\\\nb", OBDLog.format("a\\nb"));
    }
}
