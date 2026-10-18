package io.github.brainboxemb.eventtiming.timingpoint.platform.memory;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ReusableObjectPoolTest {

    @Test
    public void precreatesStockAndGrowsOnlyWhenExhausted() {
        AtomicInteger created = new AtomicInteger();
        ReusableObjectPool<Value> pool =
                new ReusableObjectPool<Value>(
                        2,
                        () -> new Value(created.incrementAndGet()));

        assertEquals(2, created.get());
        assertEquals(2, pool.initialSize());
        assertEquals(2, pool.size());
        assertEquals(0, pool.inUse());
        assertEquals(2, pool.available());

        Value first = pool.acquire();
        Value second = pool.acquire();
        assertEquals(2, created.get());
        assertEquals(2, pool.peakInUse());

        Value third = pool.acquire();
        assertEquals(3, created.get());
        assertEquals(3, pool.size());
        assertEquals(3, pool.peakInUse());

        pool.release(third);
        pool.release(second);
        pool.release(first);

        assertEquals(0, pool.inUse());
        assertEquals(3, pool.available());

        Value reused = pool.acquire();
        assertSame(first, reused);
        assertEquals(3, created.get());
    }

    private static final class Value {
        private final int id;

        private Value(int id) {
            this.id = id;
        }

        @SuppressWarnings("unused")
        private int id() {
            return id;
        }
    }
}
