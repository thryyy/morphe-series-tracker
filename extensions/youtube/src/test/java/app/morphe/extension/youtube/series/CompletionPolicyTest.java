package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

public class CompletionPolicyTest {
    @Test
    public void defaultKeepsPercentageAndThirtySecondRules() {
        assertFalse(CompletionPolicy.completed(919_999, 1_000_000, 92, 30));
        assertTrue(CompletionPolicy.completed(920_000, 1_000_000, 92, 30));
        assertFalse(CompletionPolicy.completed(69_999, 100_000, 92, 30));
        assertTrue(CompletionPolicy.completed(70_000, 100_000, 92, 30));
    }

    @Test
    public void shortVideosRequirePercentage() {
        assertFalse(CompletionPolicy.completed(30_000, 60_000, 92, 30));
        assertTrue(CompletionPolicy.completed(55_200, 60_000, 92, 30));
    }

    @Test
    public void stricterPercentageAndDisabledTimeShortcutAreRespected() {
        assertFalse(CompletionPolicy.completed(97_999, 100_000, 98, 0));
        assertTrue(CompletionPolicy.completed(98_000, 100_000, 98, 0));
        assertFalse(CompletionPolicy.completed(99_999, 100_000, 100, 0));
        assertTrue(CompletionPolicy.completed(100_000, 100_000, 100, 0));
    }

    @Test
    public void timeShortcutIsConfigurable() {
        assertFalse(CompletionPolicy.completed(84_999, 100_000, 98, 15));
        assertTrue(CompletionPolicy.completed(85_000, 100_000, 98, 15));
    }

    @Test
    public void malformedImportsFallbackAndLargeDurationsDoNotOverflow() {
        assertEquals(92, CompletionPolicy.percent(0));
        assertEquals(92, CompletionPolicy.percent(101));
        assertEquals(30, CompletionPolicy.seconds(-1));
        assertEquals(30, CompletionPolicy.seconds(Integer.MAX_VALUE));
        assertFalse(CompletionPolicy.completed(0, Long.MAX_VALUE, 100, 0));
        assertTrue(CompletionPolicy.completed(Long.MAX_VALUE, Long.MAX_VALUE, 100, 0));
        assertFalse(CompletionPolicy.completed(0, 0, 92, 30));
    }
}
