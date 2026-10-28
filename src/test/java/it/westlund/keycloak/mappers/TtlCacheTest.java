package it.westlund.keycloak.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class TtlCacheTest {

    private final AtomicLong now = new AtomicLong(1_000);

    @Test
    void returnsValueWithinTtl() {
        TtlCache<String, String> cache = new TtlCache<>(10, now::get);
        cache.put("k", "v", 5_000);

        now.addAndGet(4_999);
        assertEquals("v", cache.get("k"));
    }

    @Test
    void expiresAtTtlAndRemovesEntry() {
        TtlCache<String, String> cache = new TtlCache<>(10, now::get);
        cache.put("k", "v", 5_000);

        now.addAndGet(5_000);
        assertNull(cache.get("k"));
        assertEquals(0, cache.size());
    }

    @Test
    void zeroTtlDisablesCaching() {
        TtlCache<String, String> cache = new TtlCache<>(10, now::get);
        cache.put("k", "v", 0);

        assertNull(cache.get("k"));
        assertEquals(0, cache.size());
    }

    @Test
    void putRefreshesExpiry() {
        TtlCache<String, String> cache = new TtlCache<>(10, now::get);
        cache.put("k", "v1", 1_000);
        now.addAndGet(900);
        cache.put("k", "v2", 1_000);
        now.addAndGet(900);

        assertEquals("v2", cache.get("k"));
    }

    @Test
    void fullCachePurgesExpiredEntriesFirst() {
        TtlCache<String, String> cache = new TtlCache<>(2, now::get);
        cache.put("short", "1", 100);
        cache.put("long", "2", 10_000);
        now.addAndGet(200); // "short" is now expired

        cache.put("new", "3", 10_000);

        assertEquals(2, cache.size());
        assertEquals("2", cache.get("long"));
        assertEquals("3", cache.get("new"));
    }

    @Test
    void fullCacheEvictsEntryClosestToExpiry() {
        TtlCache<String, String> cache = new TtlCache<>(2, now::get);
        cache.put("a", "1", 5_000);
        cache.put("b", "2", 1_000); // expires first
        cache.put("c", "3", 9_000);

        assertEquals(2, cache.size());
        assertNull(cache.get("b"));
        assertEquals("1", cache.get("a"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    void updatingExistingKeyWhenFullDoesNotEvict() {
        TtlCache<String, String> cache = new TtlCache<>(2, now::get);
        cache.put("a", "1", 5_000);
        cache.put("b", "2", 1_000);
        cache.put("a", "1b", 5_000);

        assertEquals("1b", cache.get("a"));
        assertEquals("2", cache.get("b"));
    }

    @Test
    void rejectsNonPositiveMaxEntries() {
        assertThrows(IllegalArgumentException.class, () -> new TtlCache<String, String>(0, now::get));
    }

    @Test
    void concurrentPutsStayNearBound() throws InterruptedException {
        int max = 50;
        int threads = 8;
        TtlCache<Integer, Integer> cache = new TtlCache<>(max, System::currentTimeMillis);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int offset = t * 10_000;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    cache.put(offset + i, i, 60_000);
                    cache.get(offset + i / 2);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // A racing put may overshoot by at most one entry per thread.
        assertTrue(cache.size() <= max + threads, "size " + cache.size());
    }
}
