package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class TrackerServiceTest {
    private Context context;
    private String file;
    private TrackerRepository repository;
    private TrackerService service;
    private final ManualExecutor storage = new ManualExecutor();
    private final ManualExecutor network = new ManualExecutor();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> errors = new ArrayList<>();

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        file = "service-" + UUID.randomUUID() + ".db";
        repository = new TrackerRepository(context, file);
        String json =
                "{\"contents\":{\"singleColumnBrowseResultsRenderer\":{\"tabs\":[{\"tabRenderer\":{\"content\":{\"itemSectionRenderer\":{\"contents\":[{\"playlistVideoListRenderer\":{\"contents\":[{\"playlistVideoRenderer\":{\"videoId\":\"bbbbbbbbbbb\"}}]}}]}}}}]}}}";
        CatalogClient client =
                new CatalogClient(
                        (body, timeout, limit) -> {
                            requests.incrementAndGet();
                            return new CatalogClient.Reply(200, json, json.length(), 0);
                        },
                        () -> 0);
        service = new TrackerService(repository, client, storage, network);
        drainStorage();
    }

    @After
    public void close() {
        storage.shutdownNow();
        network.shutdownNow();
        repository.close();
        context.deleteDatabase(file);
    }

    private void save(String id) {
        repository.saveSeries(id, id, "", TrackerRepositoryTest.catalog("aaaaaaaaaaa"), 1);
    }

    private void drainStorage() {
        for (int i = 0; i < 100; i++) {
            storage.runAll();
            shadowOf(Looper.getMainLooper()).idle();
            if (storage.queue.isEmpty()) return;
        }
        fail("Storage callbacks did not settle");
    }

    @Test
    public void retryAfterInitializationFailureRestoresMembershipAndInterruptedFetches() {
        save("PLone");
        repository.beginRefresh("PLone");
        repository.getWritableDatabase().execSQL("PRAGMA query_only=ON");
        service = new TrackerService(repository, new CatalogClient(), storage, network);
        drainStorage();
        assertFalse(service.storageError().isEmpty());
        assertFalse(service.isTracked("aaaaaaaaaaa"));
        repository.getWritableDatabase().execSQL("PRAGMA query_only=OFF");
        service.retryStorage(errors::add);
        drainStorage();
        assertEquals("", service.storageError());
        assertTrue(service.isTracked("aaaaaaaaaaa"));
        assertNotEquals("loading", repository.series("PLone").status);
        assertEquals(1, repository.series("PLone").revision);
    }

    @Test
    public void fullQueueReportsRejectedReadsAndWritesWithoutPoisoningStorage() {
        storage.reject = true;
        AtomicInteger successes = new AtomicInteger();
        service.library(rows -> successes.incrementAndGet(), errors::add);
        service.save("PLone", "One", "", null, successes::incrementAndGet, errors::add);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(
                Arrays.asList(
                        "series_tracker_error_storage_busy", "series_tracker_error_storage_busy"),
                errors);
        assertEquals("", service.storageError());
        assertEquals(0, successes.get());
        storage.reject = false;
        service.save("PLone", "One", "", null, successes::incrementAndGet, errors::add);
        drainStorage();
        assertEquals(1, successes.get());
        assertEquals(1, repository.library().size());
    }

    @Test
    public void previewCancellationAvoidsQueuedRequestAndSaturationHasAnError() {
        Future<?> preview =
                service.preview(
                        "PLone", catalog -> fail("Cancelled preview completed"), errors::add);
        assertTrue(preview.cancel(true));
        network.runAll();
        assertEquals(0, requests.get());
        network.reject = true;
        assertNull(
                service.preview(
                        "PLone", catalog -> fail("Rejected preview completed"), errors::add));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList("series_tracker_error_catalog_busy"), errors);
    }

    @Test
    public void completedRefreshWaitsForFullStorageQueueThenPublishes() {
        save("PLone");
        service.refresh("PLone", errors::add);
        drainStorage();
        storage.reject = true;
        network.runAll();
        assertEquals("loading", repository.series("PLone").status);
        assertEquals(1, repository.series("PLone").revision);
        storage.reject = false;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        drainStorage();
        assertEquals("complete", repository.series("PLone").status);
        assertEquals(2, repository.series("PLone").revision);
        assertTrue(errors.isEmpty());
    }

    @Test
    public void failedPublicationRetainsCacheAndCanRetryWithoutAnotherRequest() {
        save("PLone");
        service.refresh("PLone", errors::add);
        drainStorage();
        network.runAll();
        repository.getWritableDatabase().execSQL("PRAGMA query_only=ON");
        drainStorage();
        assertFalse(service.storageError().isEmpty());
        assertEquals("aaaaaaaaaaa", repository.series("PLone").episodes.get(0).videoId);
        repository.getWritableDatabase().execSQL("PRAGMA query_only=OFF");
        service.retryStorage(errors::add);
        drainStorage();
        assertEquals("", service.storageError());
        assertEquals(2, repository.series("PLone").revision);
        assertEquals("bbbbbbbbbbb", repository.series("PLone").episodes.get(0).videoId);
        assertEquals(1, requests.get());
        service.refresh("PLone", errors::add);
        drainStorage();
        network.runAll();
        drainStorage();
        assertEquals(3, repository.series("PLone").revision);
    }

    @Test
    public void nextQuietRefreshFailureDoesNotReplayThePreviousPublication() {
        save("PLone");
        save("PLtwo");
        save("PLthree");
        service.refreshStale();
        drainStorage();
        assertEquals(2, network.queue.size());
        network.runNext();
        storage.runNext();
        String completed =
                repository.library().stream().filter(s -> s.revision == 2).findFirst().get().id;
        repository.getWritableDatabase().execSQL("PRAGMA query_only=ON");
        drainStorage();
        assertFalse(service.storageError().isEmpty());
        assertEquals(2, repository.series(completed).revision);
        repository.getWritableDatabase().execSQL("PRAGMA query_only=OFF");
        service.retryStorage(errors::add);
        drainStorage();
        network.runAll();
        drainStorage();
        assertEquals(3, requests.get());
        for (TrackerModels.Series series : repository.library()) {
            assertEquals(2, series.revision);
            assertEquals("complete", series.status);
        }
        assertEquals("", service.storageError());
    }

    @Test
    public void removedSeriesCannotReceiveAnAlreadyCompletedRefresh() {
        save("PLone");
        service.refresh("PLone", errors::add);
        drainStorage();
        service.remove("PLone", () -> {}, errors::add);
        network.runAll();
        drainStorage();
        assertTrue(repository.library().isEmpty());
        save("PLone");
        assertEquals("aaaaaaaaaaa", repository.series("PLone").episodes.get(0).videoId);
        assertEquals(1, repository.series("PLone").revision);
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean reject, stopped;

        public void execute(Runnable command) {
            if (reject || stopped) throw new RejectedExecutionException();
            queue.add(command);
        }

        void runNext() {
            queue.removeFirst().run();
        }

        void runAll() {
            while (!queue.isEmpty()) runNext();
        }

        public void shutdown() {
            stopped = true;
        }

        public List<Runnable> shutdownNow() {
            stopped = true;
            List<Runnable> remaining = new ArrayList<>(queue);
            queue.clear();
            return remaining;
        }

        public boolean isShutdown() {
            return stopped;
        }

        public boolean isTerminated() {
            return stopped && queue.isEmpty();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
