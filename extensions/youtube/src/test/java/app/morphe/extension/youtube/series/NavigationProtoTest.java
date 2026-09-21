package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class NavigationProtoTest {
    private byte[] home() {
        return NavigationProto.encode(
                Arrays.asList(
                        new NavigationProto.Field(
                                1,
                                2,
                                "FEwhat_to_watch"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        new NavigationProto.Field(5, 2, new byte[] {8, (byte) 0x82, 9}),
                        new NavigationProto.Field(99, 2, new byte[] {1, 2, 3}),
                        new NavigationProto.Field(10, 2, new byte[] {4, 5})));
    }

    @Test
    public void historyHasIndependentNativeEndpointAndLocalizedLabel() {
        byte[] item =
                NavigationProto.get(
                        NavigationProto.history(home(), "Historique"), NavigationProto.ITEM);
        assertEquals("FEhistory", NavigationProto.string(item, 1));
        assertEquals(
                "FEhistory",
                NavigationProto.string(
                        NavigationProto.get(NavigationProto.get(item, 2), NavigationProto.BROWSE),
                        2));
        assertEquals(
                "Historique",
                NavigationProto.string(
                        NavigationProto.get(NavigationProto.get(item, 4), 75730170), 2));
        assertEquals(
                "Historique",
                NavigationProto.string(NavigationProto.get(NavigationProto.get(item, 3), 1), 1));
        assertEquals(2, NavigationProto.icon(item));
        assertEquals("history", NavigationProto.kind(item));
        assertArrayEquals(new byte[] {1, 2, 3}, NavigationProto.get(item, 99));
        assertEquals(0, NavigationProto.get(item, 10).length);
        assertEquals("FEwhat_to_watch", NavigationProto.string(home(), 1));
    }

    @Test
    public void legacyStyleUsesLegacyHistoryIcon() {
        byte[] item = NavigationProto.replace(home(), 5, new byte[] {8, (byte) 0x96, 3});
        assertEquals(
                2,
                NavigationProto.icon(
                        NavigationProto.get(
                                NavigationProto.history(item, "History"), NavigationProto.ITEM)));
    }

    @Test
    public void copiedSearchRendererCannotReplaceHome() {
        byte[] search = NavigationProto.replace(home(), 5, new byte[] {8, 1});
        assertEquals("other", NavigationProto.kind(search));
        assertEquals("home", NavigationProto.kind(home()));
    }

    @Test
    public void missingNotificationCanUseNativeActivityDestination() {
        byte[] item =
                NavigationProto.get(
                        NavigationProto.destination(home(), "FEactivity", "Notifications", 1156),
                        NavigationProto.ITEM);
        assertEquals("notifications", NavigationProto.kind(item));
        assertEquals(
                "FEactivity",
                NavigationProto.string(
                        NavigationProto.get(NavigationProto.get(item, 2), NavigationProto.BROWSE),
                        2));
        assertEquals(1156, NavigationProto.icon(item));
    }

    @Test
    public void wireEditorRoundTripsUnknownScalarFields() {
        byte[] data = {8, (byte) 0xff, 1, 17, 1, 2, 3, 4, 5, 6, 7, 8, 29, 1, 2, 3, 4, 34, 2, 7, 8};
        assertArrayEquals(data, NavigationProto.encode(NavigationProto.read(data)));
    }

    @Test
    public void malformedAndOversizedFieldsAreRejected() {
        for (byte[] invalid :
                new byte[][] {{0}, {10, 8, 1}, {9, 1}, {8, (byte) 128}, {11}, new byte[1_048_577]})
            assertThrows(IllegalArgumentException.class, () -> NavigationProto.read(invalid));
    }
}
