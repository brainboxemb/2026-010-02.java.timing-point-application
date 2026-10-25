package oss.relay.timing.timingpoint.platform.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * On-demand JDK runtime observations used by engineering characterization.
 *
 * <p>This class does not sample continuously and owns no background thread.
 * Capturing a snapshot is an explicit diagnostic operation, so any allocations
 * made by the management APIs occur outside the per-registration hot path.</p>
 */
public final class RuntimeObservation {
    /** Prevents construction of this stateless observation utility. */
    private RuntimeObservation() {
    }

    /** Captures one process-wide JVM observation snapshot. */
    public static Snapshot capture() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        long gcCount = 0L;
        long gcTimeMillis = 0L;
        boolean hasKnownGcCount = false;
        boolean hasKnownGcTime = false;
        for (GarbageCollectorMXBean collector :
                ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            if (count >= 0L) {
                gcCount += count;
                hasKnownGcCount = true;
            }
            long time = collector.getCollectionTime();
            if (time >= 0L) {
                gcTimeMillis += time;
                hasKnownGcTime = true;
            }
        }

        return new Snapshot(
                memory.getHeapMemoryUsage().getUsed(),
                threads.getThreadCount(),
                hasKnownGcCount ? gcCount : -1L,
                hasKnownGcTime ? gcTimeMillis : -1L);
    }

    /**
     * Returns aggregate CPU time for live threads whose names start with one of
     * the supplied prefixes.
     *
     * <p>The method does not enable JVM thread CPU accounting. When the selected
     * JVM does not support it or has it disabled, {@code -1} is returned.</p>
     */
    public static long threadCpuTimeNanos(
            String... threadNamePrefixes) {
        if (threadNamePrefixes == null
                || threadNamePrefixes.length == 0) {
            throw new IllegalArgumentException(
                    "threadNamePrefixes must not be empty");
        }

        ThreadMXBean threads =
                ManagementFactory.getThreadMXBean();
        if (!threads.isThreadCpuTimeSupported()
                || !threads.isThreadCpuTimeEnabled()) {
            return -1L;
        }

        long total = 0L;
        boolean observed = false;
        long[] ids = threads.getAllThreadIds();
        ThreadInfo[] infos = threads.getThreadInfo(ids);
        for (int index = 0; index < ids.length; index++) {
            ThreadInfo info = infos[index];
            if (info == null
                    || !matchesPrefix(
                            info.getThreadName(),
                            threadNamePrefixes)) {
                continue;
            }

            long cpuTime =
                    threads.getThreadCpuTime(
                            ids[index]);
            if (cpuTime >= 0L) {
                total += cpuTime;
                observed = true;
            }
        }
        return observed ? total : -1L;
    }

    private static boolean matchesPrefix(
            String threadName,
            String[] prefixes) {
        for (String prefix : prefixes) {
            if (prefix == null
                    || prefix.isEmpty()) {
                throw new IllegalArgumentException(
                        "threadNamePrefix must not be blank");
            }
            if (threadName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Immutable explicit-capture result; not allocated on event processing. */
    public static final class Snapshot {
        private final long heapUsedBytes;
        private final int liveThreadCount;
        private final long gcCollectionCount;
        private final long gcCollectionTimeMillis;

        /** Creates one immutable explicit-capture JVM observation snapshot. */
        private Snapshot(
                long heapUsedBytes,
                int liveThreadCount,
                long gcCollectionCount,
                long gcCollectionTimeMillis) {
            this.heapUsedBytes = heapUsedBytes;
            this.liveThreadCount = liveThreadCount;
            this.gcCollectionCount = gcCollectionCount;
            this.gcCollectionTimeMillis = gcCollectionTimeMillis;
        }

        /** Returns currently used heap bytes at capture time. */
        public long heapUsedBytes() {
            return heapUsedBytes;
        }

        /** Returns the live JVM thread count at capture time. */
        public int liveThreadCount() {
            return liveThreadCount;
        }

        /** Returns aggregate GC count, or -1 when the JVM reports it as unknown. */
        public long gcCollectionCount() {
            return gcCollectionCount;
        }

        /** Returns aggregate GC time in milliseconds, or -1 when unknown. */
        public long gcCollectionTimeMillis() {
            return gcCollectionTimeMillis;
        }
    }
}
