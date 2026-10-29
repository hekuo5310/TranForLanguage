package com.github.tranforcpp.core;

import java.util.HashMap;
import java.util.Map;

/** Monotonic, per-source event throttle. Call from the owning event thread. */
public final class EventRateLimiter<K> {
    private final long interval;
    private final Map<K, Long> previous = new HashMap<>();
    public EventRateLimiter(long interval) { this.interval = Math.max(0, interval); }
    public boolean allow(K key, long now) {
        Long last = previous.get(key);
        if (last != null && now - last < interval) return false;
        previous.put(key, now); return true;
    }
    public void forget(K key) { previous.remove(key); }
    public void clear() { previous.clear(); }
}
