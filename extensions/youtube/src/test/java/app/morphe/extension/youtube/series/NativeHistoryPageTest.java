package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class NativeHistoryPageTest {
    private byte[] wrap(byte[] bytes, int... path) {
        for (int i = path.length - 1; i >= 0; i--) bytes = NavigationProto.field(path[i], bytes);
        return bytes;
    }

    private byte[] join(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] bytes : arrays) out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }

    private byte[] row(String id, int percent) {
        return wrap(
                join(
                        NavigationProto.text(1, id),
                        wrap(new byte[] {8, (byte) percent}, 32, 110282477)),
                1,
                50195462,
                1,
                50630979);
    }

    @Test
    public void initialPageReadsOnlyVideoRowsAndTheirOwnProgress() {
        byte[] data =
                wrap(
                        join(
                                row("abcdefghijk", 56),
                                row("bcdefghijkl", 100),
                                wrap(NavigationProto.text(1, "NEXT_PAGE"), 2, 52047593),
                                wrap(NavigationProto.text(1, "unrelated12"), 25, 50630979)),
                        9,
                        58173949,
                        1,
                        58174010,
                        4,
                        49399797);
        NativeHistoryPage page = NativeHistoryPage.parse(data);
        assertEquals(2, page.percentages.size());
        assertEquals(Integer.valueOf(56), page.percentages.get("abcdefghijk"));
        assertEquals(Integer.valueOf(100), page.percentages.get("bcdefghijkl"));
        assertEquals("NEXT_PAGE", page.continuation);
    }

    @Test
    public void continuationUsesVerifiedSeparateResponseContainer() {
        NativeHistoryPage page =
                NativeHistoryPage.parse(wrap(row("abcdefghijk", 92), 10, 49399797));
        assertEquals(Integer.valueOf(92), page.percentages.get("abcdefghijk"));
    }

    @Test
    public void missingAndInvalidPercentagesDoNotInventCompletion() {
        byte[] missing = wrap(NavigationProto.text(1, "abcdefghijk"), 1, 50195462, 1, 50630979);
        NativeHistoryPage page =
                NativeHistoryPage.parse(wrap(join(missing, row("bcdefghijkl", 101)), 10, 49399797));
        assertTrue(page.percentages.isEmpty());
    }

    @Test
    public void duplicateOlderHistoryEntryCannotReplaceNewest() {
        NativeHistoryPage page =
                NativeHistoryPage.parse(
                        wrap(join(row("abcdefghijk", 10), row("abcdefghijk", 100)), 10, 49399797));
        assertEquals(Integer.valueOf(10), page.percentages.get("abcdefghijk"));
    }

    @Test
    public void malformedAndUnsupportedResponsesAreErrorsNotEmptyLibraries() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeHistoryPage.parse(new byte[] {10, 127}));
        assertThrows(IllegalArgumentException.class, () -> NativeHistoryPage.parse(new byte[0]));
    }

    private byte[] endpointRow(String rowId, String endpointId, float seconds) {
        byte[] value =
                java.nio.ByteBuffer.allocate(4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putFloat(seconds)
                        .array();
        byte[] start =
                NavigationProto.encode(
                        java.util.Collections.singletonList(
                                new NavigationProto.Field(7, 5, value)));
        return wrap(
                join(
                        NavigationProto.text(1, rowId),
                        wrap(join(NavigationProto.text(1, endpointId), start), 8, 48687757)),
                1,
                50195462,
                1,
                50630979);
    }

    @Test
    public void nativeFloatSecondsMatchHostTruncationAndRetainZeroPresence() {
        NativeHistoryPage page =
                NativeHistoryPage.parse(
                        wrap(
                                join(
                                        endpointRow("abcdefghijk", "abcdefghijk", 2010.75f),
                                        endpointRow("bcdefghijkl", "bcdefghijkl", 0)),
                                10,
                                49399797));
        assertEquals(Long.valueOf(2010000), page.rows.get("abcdefghijk").positionMs);
        assertEquals(Long.valueOf(0), page.rows.get("bcdefghijkl").positionMs);
    }

    @Test
    public void endpointMustBelongToItsHistoryVideoAndTimeMustBeFinite() {
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 604801}) {
            assertNull(
                    NativeHistoryPage.parse(
                                    wrap(
                                            endpointRow("abcdefghijk", "abcdefghijk", invalid),
                                            10,
                                            49399797))
                            .rows
                            .get("abcdefghijk")
                            .positionMs);
        }
        assertNull(
                NativeHistoryPage.parse(
                                wrap(endpointRow("abcdefghijk", "bcdefghijkl", 2010), 10, 49399797))
                        .rows
                        .get("abcdefghijk")
                        .positionMs);
    }

    @Test
    public void olderDuplicateCannotSupplyMissingTimestampOnNewestRow() {
        NativeHistoryPage page =
                NativeHistoryPage.parse(
                        wrap(
                                join(
                                        row("abcdefghijk", 20),
                                        endpointRow("abcdefghijk", "abcdefghijk", 2010)),
                                10,
                                49399797));
        assertNull(page.rows.get("abcdefghijk").positionMs);
    }
}
