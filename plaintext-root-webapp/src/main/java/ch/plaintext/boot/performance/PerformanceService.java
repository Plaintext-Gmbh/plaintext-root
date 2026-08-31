/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
public class PerformanceService {

    /** Key: method signature (label) */
    private final ConcurrentMap<String, PlaintextMethodStats> stats = new ConcurrentHashMap<>();

    /** This list is used by JSF/PrimeFaces (as it used to be with Jamon). */
    private final List<Object[]> dataArr = Collections.synchronizedList(new ArrayList<>());

    /** Called by the aspect. */
    public void record(String label, long durationNanos) {
        PlaintextMethodStats s = stats.computeIfAbsent(label, PlaintextMethodStats::new);
        s.record(durationNanos);
    }

    /**
     * Rebuilds the table to be displayed from the current stats.
     * You call this method from the page via #{performanceService.reload()} and from a button.
     */
    public void reload() {
        synchronized (dataArr) {
            dataArr.clear();

            for (PlaintextMethodStats s : stats.values()) {

                long count      = s.getCount().sum();
                long totalNs    = s.getTotalTimeNanos().sum();
                long minNs      = s.getMinTimeNanos().get();
                long maxNs      = s.getMaxTimeNanos().get();
                long lastNs     = s.getLastTimeNanos().get();
                long firstMsRaw = s.getFirstAccessMillis().get();
                long lastMsRaw  = s.getLastAccessMillis().get();

                double totalMs = nanosToMillis(totalNs);

                double avgMs = 0.0;
                if (count > 0) {
                    avgMs = totalMs / (double) count;   // average in ms
                }

                double minMs  = (minNs == Long.MAX_VALUE) ? 0.0 : nanosToMillis(minNs);
                double maxMs  = nanosToMillis(maxNs);
                double lastMs = nanosToMillis(lastNs);

                Date firstAccess = (firstMsRaw == 0L) ? null : new Date(firstMsRaw);
                Date lastAccess  = (lastMsRaw == 0L) ? null : new Date(lastMsRaw);

                Object[] row = new Object[14];

                // indices matched exactly to your performance.xhtml:
                row[0]  = "method";                 // currently unused
                row[1]  = s.getLabel() + ", ms.";   // method
                row[2]  = count;                    // hits
                row[3]  = avgMs;                    // average (ms)
                row[4]  = totalMs;                  // total (ms) (optional)
                row[5]  = lastMs;                   // last value (optional)
                row[6]  = null;                     // free
                row[7]  = minMs;                    // min (ms)
                row[8]  = maxMs;                    // max (ms)
                row[9]  = 0L;                       // Active (dummy)
                row[10] = 0.0;                      // Avg Active (dummy)
                row[11] = 0L;                       // Max Active (dummy)
                row[12] = firstAccess;              // First Access
                row[13] = lastAccess;               // last call

                dataArr.add(row);
            }
        }

        log.debug("reload() built {} performance rows", dataArr.size());
    }

    /** JSF/PrimeFaces accesses this. */
    public List<Object[]> getDataArr() {
        return dataArr;
    }

    public void reset() {
        stats.clear();
        synchronized (dataArr) {
            dataArr.clear();
        }
        log.info("Metrics reset – all collected timings and rows cleared.");
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }

    @Getter
    private static final class PlaintextMethodStats {

        private final String label;
        private final LongAdder count = new LongAdder();
        private final LongAdder totalTimeNanos = new LongAdder();
        private final AtomicLong minTimeNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxTimeNanos = new AtomicLong(0);
        private final AtomicLong lastTimeNanos = new AtomicLong(0);

        private final AtomicLong firstAccessMillis = new AtomicLong(0L);
        private final AtomicLong lastAccessMillis  = new AtomicLong(0L);

        private PlaintextMethodStats(String label) {
            this.label = label;
        }

        void record(long durationNanos) {
            long now = System.currentTimeMillis();

            count.increment();
            totalTimeNanos.add(durationNanos);
            lastTimeNanos.set(durationNanos);

            minTimeNanos.getAndAccumulate(durationNanos, Math::min);
            maxTimeNanos.getAndAccumulate(durationNanos, Math::max);

            firstAccessMillis.compareAndSet(0L, now);
            lastAccessMillis.set(now);
        }
    }
}
