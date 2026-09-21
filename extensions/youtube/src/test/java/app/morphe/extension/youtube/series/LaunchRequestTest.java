package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

public class LaunchRequestTest {
    @Test
    public void zeroIsExplicitAndMillisecondsAreTruncatedOnlyAtLaunch() {
        assertEquals(0, new LaunchRequest("4aQu8XID5YE", "", 0).seconds());
        assertEquals(14, new LaunchRequest("4aQu8XID5YE", "PL_example", 14999).seconds());
    }

    @Test
    public void rejectsInjectedQueryParametersAndInvalidTimes() {
        assertThrows(
                IllegalArgumentException.class, () -> new LaunchRequest("4aQu8XID5YE&", "", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LaunchRequest("4aQu8XID5YE", "PL_x&t=9", 0));
        assertThrows(
                IllegalArgumentException.class, () -> new LaunchRequest("4aQu8XID5YE", "", -1));
    }
}
