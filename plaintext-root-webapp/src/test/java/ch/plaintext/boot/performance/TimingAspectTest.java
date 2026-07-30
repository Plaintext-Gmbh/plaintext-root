/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for TimingAspect - AOP timing of method calls.
 */
@ExtendWith(MockitoExtension.class)
class TimingAspectTest {

    @Mock
    private PerformanceService performanceService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Signature signature;

    private TimingAspect timingAspect;

    @BeforeEach
    void setUp() {
        timingAspect = new TimingAspect(performanceService);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toShortString()).thenReturn("TestClass.testMethod()");
    }

    @Test
    void time_shouldCallProceedAndRecordTiming() throws Throwable {
        when(joinPoint.proceed()).thenReturn("result");

        Object result = timingAspect.time(joinPoint);

        assertEquals("result", result);
        verify(joinPoint).proceed();
        verify(performanceService).record(eq("TestClass.testMethod()"), anyLong());
    }

    @Test
    void time_shouldRecordTimingEvenOnException() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new RuntimeException("test error"));

        assertThrows(RuntimeException.class, () -> timingAspect.time(joinPoint));

        verify(performanceService).record(eq("TestClass.testMethod()"), anyLong());
    }

    @Test
    void time_shouldReturnNullResult() throws Throwable {
        when(joinPoint.proceed()).thenReturn(null);

        Object result = timingAspect.time(joinPoint);

        assertNull(result);
        verify(performanceService).record(anyString(), anyLong());
    }

    @Test
    void time_shouldRecordPositiveDuration() throws Throwable {
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            Thread.sleep(10); // Small delay to ensure positive duration
            return "result";
        });

        timingAspect.time(joinPoint);

        verify(performanceService).record(eq("TestClass.testMethod()"), longThat(duration -> duration > 0));
    }
}
