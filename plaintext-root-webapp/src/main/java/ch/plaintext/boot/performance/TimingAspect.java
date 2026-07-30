/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class TimingAspect {

    private final PerformanceService performanceService;

    // WICHTIG: @Lazy hier bricht den Circle
    public TimingAspect(@Lazy PerformanceService performanceService) {
        this.performanceService = performanceService;
    }

    /**
     * Misst NUR Anwendungscode, NICHT das performance-Paket selbst.
     *
     * Passe die Packages nach Bedarf an.
     * Beispiel: Services + Controller + Cron in deinem Projekt.
     */
    @Around("within(ch.plaintext..*) && !within(ch.plaintext.boot.performance..*)")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        String label = pjp.getSignature().toShortString();
        long start = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long duration = System.nanoTime() - start;
            performanceService.record(label, duration);

            if (log.isDebugEnabled()) {
                log.debug("{} took {} ms (last value)", label, duration / 1_000_000.0);
            }
        }
    }
}
