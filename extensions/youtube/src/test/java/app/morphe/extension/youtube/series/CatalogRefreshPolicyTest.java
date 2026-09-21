package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

public class CatalogRefreshPolicyTest {
    @Test
    public void freshCatalogsAreReusedAndStaleCatalogsBecomeDue() {
        long fetched = 1000000;
        assertFalse(
                CatalogRefreshPolicy.due(
                        fetched, 0, 0, fetched + CatalogRefreshPolicy.STALE_MS - 1));
        assertTrue(
                CatalogRefreshPolicy.due(fetched, 0, 0, fetched + CatalogRefreshPolicy.STALE_MS));
        assertTrue(CatalogRefreshPolicy.due(0, 0, 0, fetched));
    }

    @Test
    public void reopeningCannotLoopAFailedRequestOrBypassServerBackoff() {
        long now = CatalogRefreshPolicy.STALE_MS * 2;
        assertFalse(CatalogRefreshPolicy.due(1, now - 1, 0, now));
        assertFalse(CatalogRefreshPolicy.due(1, 0, now + 1, now));
        assertTrue(CatalogRefreshPolicy.due(1, now - CatalogRefreshPolicy.RETRY_MS, now, now));
    }

    @Test
    public void clockMovedBackDoesNotRefreshOnEveryOpen() {
        assertTrue(CatalogRefreshPolicy.due(2000, 0, 0, 1000));
        assertFalse(CatalogRefreshPolicy.due(2000, 1000, 0, 999));
    }
}
