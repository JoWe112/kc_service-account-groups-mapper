package it.westlund.keycloak.mappers;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Small thread-safe TTL cache with an upper bound on the number of entries.
 *
 * <p>The TTL is supplied per {@link #put} call (not per cache) because one mapper
 * provider instance is shared by every mapper configuration in the server, and each
 * configuration can have its own TTL.
 *
 * <p>When the cache is full, expired entries are purged first; if it is still full, the
 * entry closest to expiry is evicted. Eviction is O(n), which is fine for the intended
 * size (thousands of clients at most) and keeps the implementation dependency-free.
 */
final class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMillis) {
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final LongSupplier clock;

    /**
     * @param maxEntries upper bound on the number of entries; must be positive
     * @param clock      millisecond clock, injectable for tests (use {@code System::currentTimeMillis})
     */
    TtlCache(int maxEntries, LongSupplier clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Returns the cached value, or {@code null} if absent or expired. Expired entries are
     * removed on access.
     */
    V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry, clock.getAsLong())) {
            // remove(key, value) so a concurrent fresh put is not dropped
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    /**
     * Stores a value for {@code ttlMillis}. A non-positive TTL is a no-op (caching disabled).
     */
    void put(K key, V value, long ttlMillis) {
        if (ttlMillis <= 0) {
            return;
        }
        long now = clock.getAsLong();
        if (!entries.containsKey(key) && entries.size() >= maxEntries) {
            makeRoom(now);
        }
        entries.put(key, new Entry<>(value, now + ttlMillis));
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
    }

    /**
     * Frees at least one slot: first drops expired entries, then (if needed) evicts entries
     * closest to expiry until below the bound. Concurrent puts may briefly overshoot the
     * bound by the number of racing threads, which is acceptable for a cache.
     */
    private void makeRoom(long now) {
        entries.entrySet().removeIf(e -> isExpired(e.getValue(), now));
        while (entries.size() >= maxEntries) {
            entries.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().expiresAtMillis()))
                    .ifPresent(e -> entries.remove(e.getKey(), e.getValue()));
        }
    }

    private static boolean isExpired(Entry<?> entry, long now) {
        return now >= entry.expiresAtMillis();
    }
}
