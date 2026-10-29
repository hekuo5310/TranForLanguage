package com.github.tranforcpp.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class EventRateLimiterTest {
    @Test public void eachPlayerHasIndependentWindowsAndQuitRemovesHistory() {
        EventRateLimiter<String> limiter = new EventRateLimiter<>(100);
        assertTrue(limiter.allow("one", 0));
        assertFalse(limiter.allow("one", 99));
        assertTrue(limiter.allow("two", 99));
        assertTrue(limiter.allow("one", 100));
        assertFalse(limiter.allow("two", 100));
        limiter.forget("two"); assertTrue(limiter.allow("two", 100));
        limiter.clear(); assertTrue(limiter.allow("one", 101));
    }
    @Test public void zeroIntervalDisablesThrottling() {
        EventRateLimiter<String> limiter = new EventRateLimiter<>(0);
        for (int i = 0; i < 1000; i++) assertTrue(limiter.allow("one", 0));
    }
}
