package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class NativeHistoryTransportTest {
    private static class Fake implements NativeHistoryTransport.Source {
        final CompletableFuture<Object> future = new CompletableFuture<>();
        byte[] bytes = new byte[] {8, 1};
        int requests, decodes;
        Runnable onRequest = () -> {};

        public Future<?> seriesTrackerHistory(String continuation, Executor executor) {
            requests++;
            onRequest.run();
            return future;
        }

        public byte[] seriesTrackerHistoryBytes(Object response) {
            decodes++;
            return bytes;
        }
    }

    @Test
    public void successfulResponseIsReturned() throws Exception {
        Fake fake = new Fake();
        fake.future.complete(new Object());
        assertArrayEquals(
                fake.bytes,
                NativeHistoryTransport.await(
                        fake, fake.future, () -> true, 1, TimeUnit.SECONDS, true));
    }

    @Test
    public void disallowedAccountNeverSendsRequest() throws Exception {
        Fake fake = new Fake();
        assertThrows(
                CancellationException.class,
                () ->
                        NativeHistoryTransport.await(
                                fake, fake.future, () -> false, 1, TimeUnit.SECONDS, true));
        assertEquals(0, fake.requests);
    }

    @Test
    public void accountSwitchDiscardsResponseBeforeDecoding() throws Exception {
        Fake fake = new Fake();
        AtomicBoolean account = new AtomicBoolean(true);
        fake.future.complete(new Object());
        java.util.concurrent.atomic.AtomicInteger checks =
                new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        NativeHistoryTransport.await(
                                fake,
                                fake.future,
                                () -> checks.incrementAndGet() == 1,
                                1,
                                TimeUnit.SECONDS,
                                true));
        assertEquals(0, fake.decodes);
    }

    @Test
    public void timeoutCancelsRequest() throws Exception {
        Fake fake = new Fake();
        assertThrows(
                TimeoutException.class,
                () ->
                        NativeHistoryTransport.await(
                                fake, fake.future, () -> true, 0, TimeUnit.MILLISECONDS, true));
        assertTrue(fake.future.isCancelled());
    }

    @Test
    public void interruptedWorkerCancelsAndPreservesInterrupt() throws Exception {
        Fake fake = new Fake();
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class,
                    () ->
                            NativeHistoryTransport.await(
                                    fake, fake.future, () -> true, 1, TimeUnit.SECONDS, true));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(fake.future.isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void invalidResponsesFailInsteadOfBecomingEmptyHistory() throws Exception {
        for (byte[] bytes : new byte[][] {null, new byte[0], new byte[4 * 1024 * 1024 + 1]}) {
            Fake fake = new Fake();
            fake.bytes = bytes;
            fake.future.complete(new Object());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            NativeHistoryTransport.await(
                                    fake, fake.future, () -> true, 1, TimeUnit.SECONDS, true));
        }
    }

    @Test
    public void timeoutAndAccountChangeNeverCancelObservedHostRequest() {
        Fake fake = new Fake();
        assertThrows(
                TimeoutException.class,
                () ->
                        NativeHistoryTransport.await(
                                fake, fake.future, () -> true, 0, TimeUnit.MILLISECONDS, false));
        assertFalse(fake.future.isCancelled());
        assertThrows(
                CancellationException.class,
                () ->
                        NativeHistoryTransport.await(
                                fake, fake.future, () -> false, 1, TimeUnit.SECONDS, false));
        assertFalse(fake.future.isCancelled());
    }
}
