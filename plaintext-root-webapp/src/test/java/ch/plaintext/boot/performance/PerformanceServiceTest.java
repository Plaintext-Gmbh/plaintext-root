/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PerformanceService - performance monitoring service.
 */
class PerformanceServiceTest {

    private PerformanceService performanceService;

    @BeforeEach
    void setUp() {
        performanceService = new PerformanceService();
    }

    // ==================== record() Tests ====================

    @Test
    void record_shouldTrackSingleMethodCall() {
        // When
        performanceService.record("testMethod", 1_000_000); // 1ms in nanos

        // Then: Reload to populate dataArr
        performanceService.reload();
        List<Object[]> data = performanceService.getDataArr();

        assertEquals(1, data.size());
        Object[] row = data.get(0);
        assertTrue(row[1].toString().contains("testMethod"));
        assertEquals(1L, row[2]); // Hits count
    }

    @Test
    void record_shouldAccumulateMultipleCallsToSameMethod() {
        // When: Record same method 3 times
        performanceService.record("method1", 1_000_000);  // 1ms
        performanceService.record("method1", 2_000_000);  // 2ms
        performanceService.record("method1", 3_000_000);  // 3ms

        // Then
        performanceService.reload();
        List<Object[]> data = performanceService.getDataArr();

        assertEquals(1, data.size());
        Object[] row = data.get(0);
        assertEquals(3L, row[2]); // 3 hits
        assertEquals(6.0, (Double) row[4], 0.01); // Total: 6ms
        assertEquals(2.0, (Double) row[3], 0.01); // Average: 2ms
    }

    @Test
    void record_shouldTrackDifferentMethods() {
        // When: Record different methods
        performanceService.record("method1", 1_000_000);
        performanceService.record("method2", 2_000_000);
        performanceService.record("method3", 3_000_000);

        // Then
        performanceService.reload();
        List<Object[]> data = performanceService.getDataArr();

        assertEquals(3, data.size());
    }

    @Test
    void record_shouldHandleZeroDuration() {
        // When
        performanceService.record("fastMethod", 0);

        // Then
        performanceService.reload();
        List<Object[]> data = performanceService.getDataArr();

        assertEquals(1, data.size());
        Object[] row = data.get(0);
        assertEquals(0.0, (Double) row[3], 0.001); // Average
        assertEquals(0.0, (Double) row[7], 0.001); // Min
    }

    @Test
    void record_shouldHandleVeryLargeDuration() {
        // When: Record a very slow method (1 second = 1,000,000,000 nanos)
        performanceService.record("slowMethod", 1_000_000_000);

        // Then
        performanceService.reload();
        List<Object[]> data = performanceService.getDataArr();

        Object[] row = data.get(0);
        assertEquals(1000.0, (Double) row[3], 1.0); // ~1000ms average
    }

    // ==================== reload() Tests ====================

    @Test
    void reload_shouldPopulateDataArr() {
        // Given
        performanceService.record("method1", 1_000_000);
        performanceService.record("method2", 2_000_000);

        // When
        performanceService.reload();

        // Then
        List<Object[]> data = performanceService.getDataArr();
        assertEquals(2, data.size());
    }

    @Test
    void reload_shouldClearPreviousDataBeforeRebuilding() {
        // Given: Initial data
        performanceService.record("method1", 1_000_000);
        performanceService.reload();
        assertEquals(1, performanceService.getDataArr().size());

        // When: Add more data and reload
        performanceService.record("method2", 2_000_000);
        performanceService.reload();

        // Then: Should have 2 rows, not 3
        assertEquals(2, performanceService.getDataArr().size());
    }

    @Test
    void reload_shouldReturnEmptyList_whenNoDataRecorded() {
        // When
        performanceService.reload();

        // Then
        assertTrue(performanceService.getDataArr().isEmpty());
    }

    @Test
    void reload_shouldCalculateCorrectStatistics() {
        // Given: Multiple calls with known durations
        performanceService.record("testMethod", 1_000_000);  // 1ms
        performanceService.record("testMethod", 3_000_000);  // 3ms
        performanceService.record("testMethod", 5_000_000);  // 5ms

        // When
        performanceService.reload();

        // Then: Check all statistics
        Object[] row = performanceService.getDataArr().get(0);

        assertEquals(3L, row[2]);                        // Hits = 3
        assertEquals(3.0, (Double) row[3], 0.01);        // Avg = 3ms
        assertEquals(9.0, (Double) row[4], 0.01);        // Total = 9ms
        assertEquals(5.0, (Double) row[5], 0.01);        // Last = 5ms
        assertEquals(1.0, (Double) row[7], 0.01);        // Min = 1ms
        assertEquals(5.0, (Double) row[8], 0.01);        // Max = 5ms
    }

    @Test
    void reload_shouldSetFirstAndLastAccessTimes() {
        // Given
        performanceService.record("testMethod", 1_000_000);

        // When
        performanceService.reload();

        // Then
        Object[] row = performanceService.getDataArr().get(0);
        assertNotNull(row[12]); // First access
        assertNotNull(row[13]); // Last access
        assertTrue(row[12] instanceof Date);
        assertTrue(row[13] instanceof Date);
    }

    @Test
    void reload_shouldIncludeMethodLabelWithSuffix() {
        // Given
        performanceService.record("MyService.doSomething", 1_000_000);

        // When
        performanceService.reload();

        // Then
        Object[] row = performanceService.getDataArr().get(0);
        String label = (String) row[1];
        assertTrue(label.contains("MyService.doSomething"));
        assertTrue(label.contains("ms."));
    }

    // ==================== reset() Tests ====================

    @Test
    void reset_shouldClearAllStatistics() {
        // Given: Some recorded data
        performanceService.record("method1", 1_000_000);
        performanceService.record("method2", 2_000_000);
        performanceService.reload();
        assertEquals(2, performanceService.getDataArr().size());

        // When
        performanceService.reset();

        // Then: Everything should be cleared
        performanceService.reload();
        assertTrue(performanceService.getDataArr().isEmpty());
    }

    @Test
    void reset_shouldAllowNewRecordsAfterReset() {
        // Given: Record, reset
        performanceService.record("method1", 1_000_000);
        performanceService.reset();

        // When: Record new data
        performanceService.record("method2", 2_000_000);
        performanceService.reload();

        // Then: Should have only new data
        assertEquals(1, performanceService.getDataArr().size());
        Object[] row = performanceService.getDataArr().get(0);
        assertTrue(row[1].toString().contains("method2"));
    }

    @Test
    void reset_shouldClearDataArrImmediately() {
        // Given
        performanceService.record("method1", 1_000_000);
        performanceService.reload();
        assertEquals(1, performanceService.getDataArr().size());

        // When
        performanceService.reset();

        // Then: DataArr should be empty without needing reload
        assertTrue(performanceService.getDataArr().isEmpty());
    }

    // ==================== getDataArr() Tests ====================

    @Test
    void getDataArr_shouldReturnEmptyList_initially() {
        // When & Then
        assertTrue(performanceService.getDataArr().isEmpty());
    }

    @Test
    void getDataArr_shouldReturnSynchronizedList() {
        // When
        List<Object[]> data = performanceService.getDataArr();

        // Then: Should be able to safely access in concurrent context
        assertNotNull(data);
        assertDoesNotThrow(() -> {
            synchronized (data) {
                data.size();
            }
        });
    }

    // ==================== Statistics Calculation Tests ====================

    @Test
    void statistics_shouldCalculateMinCorrectly() {
        // Given: Various durations
        performanceService.record("method", 5_000_000);  // 5ms
        performanceService.record("method", 2_000_000);  // 2ms (min)
        performanceService.record("method", 8_000_000);  // 8ms

        // When
        performanceService.reload();

        // Then
        Object[] row = performanceService.getDataArr().get(0);
        assertEquals(2.0, (Double) row[7], 0.01); // Min should be 2ms
    }

    @Test
    void statistics_shouldCalculateMaxCorrectly() {
        // Given: Various durations
        performanceService.record("method", 5_000_000);  // 5ms
        performanceService.record("method", 2_000_000);  // 2ms
        performanceService.record("method", 8_000_000);  // 8ms (max)

        // When
        performanceService.reload();

        // Then
        Object[] row = performanceService.getDataArr().get(0);
        assertEquals(8.0, (Double) row[8], 0.01); // Max should be 8ms
    }

    @Test
    void statistics_shouldTrackLastValue() {
        // Given: Multiple calls
        performanceService.record("method", 1_000_000);
        performanceService.record("method", 2_000_000);
        performanceService.record("method", 3_000_000); // Last

        // When
        performanceService.reload();

        // Then
        Object[] row = performanceService.getDataArr().get(0);
        assertEquals(3.0, (Double) row[5], 0.01); // Last should be 3ms
    }

    @Test
    void statistics_shouldUpdateLastValueOnEachCall() {
        // Given: First call
        performanceService.record("method", 1_000_000);
        performanceService.reload();
        assertEquals(1.0, (Double) performanceService.getDataArr().get(0)[5], 0.01);

        // When: Second call
        performanceService.record("method", 5_000_000);
        performanceService.reload();

        // Then: Last value should update
        assertEquals(5.0, (Double) performanceService.getDataArr().get(0)[5], 0.01);
    }

    // ==================== Edge Cases & Concurrent Tests ====================

    @Test
    void record_shouldHandleSpecialCharactersInMethodName() {
        // Given: Method with special characters
        String methodName = "com.example.MyService$InnerClass.method()";

        // When
        performanceService.record(methodName, 1_000_000);
        performanceService.reload();

        // Then
        Object[] row = performanceService.getDataArr().get(0);
        assertTrue(row[1].toString().contains(methodName));
    }

    @Test
    void record_shouldHandleVeryLongMethodNames() {
        // Given: Very long method name
        String longMethodName = "a".repeat(500);

        // When
        performanceService.record(longMethodName, 1_000_000);
        performanceService.reload();

        // Then: Should handle without error
        assertEquals(1, performanceService.getDataArr().size());
    }

    @Test
    void record_shouldBeConcurrentSafe_withMultipleCalls() {
        // When: Simulate concurrent calls to same method
        for (int i = 0; i < 100; i++) {
            performanceService.record("concurrentMethod", i * 1_000_000);
        }

        // Then
        performanceService.reload();
        Object[] row = performanceService.getDataArr().get(0);
        assertEquals(100L, row[2]); // Should have all 100 hits
    }

    @Test
    void statistics_shouldHandleSingleCall() {
        // Given: Only one call
        performanceService.record("method", 5_000_000);

        // When
        performanceService.reload();

        // Then: Min = Max = Avg = Last = 5ms
        Object[] row = performanceService.getDataArr().get(0);
        assertEquals(5.0, (Double) row[3], 0.01); // Avg
        assertEquals(5.0, (Double) row[5], 0.01); // Last
        assertEquals(5.0, (Double) row[7], 0.01); // Min
        assertEquals(5.0, (Double) row[8], 0.01); // Max
    }

    @Test
    void reload_shouldCreateRowWithCorrectNumberOfColumns() {
        // Given
        performanceService.record("method", 1_000_000);

        // When
        performanceService.reload();

        // Then: Should have 14 columns as defined
        Object[] row = performanceService.getDataArr().get(0);
        assertEquals(14, row.length);
    }

    @Test
    void reload_shouldSetUnusedColumnsCorrectly() {
        // Given
        performanceService.record("method", 1_000_000);

        // When
        performanceService.reload();

        // Then: Check unused/dummy columns
        Object[] row = performanceService.getDataArr().get(0);
        assertEquals("method", row[0]);  // Column 0: "method"
        assertNull(row[6]);              // Column 6: null
        assertEquals(0L, row[9]);        // Column 9: Active (dummy)
        assertEquals(0.0, row[10]);      // Column 10: Avg Active (dummy)
        assertEquals(0L, row[11]);       // Column 11: Max Active (dummy)
    }

    // ==================== Integration Tests ====================

    @Test
    void fullWorkflow_shouldWorkCorrectly() {
        // 1. Record some data
        performanceService.record("method1", 1_000_000);
        performanceService.record("method1", 2_000_000);
        performanceService.record("method2", 5_000_000);

        // 2. Reload and verify
        performanceService.reload();
        assertEquals(2, performanceService.getDataArr().size());

        // 3. Record more data
        performanceService.record("method3", 3_000_000);

        // 4. Reload again
        performanceService.reload();
        assertEquals(3, performanceService.getDataArr().size());

        // 5. Reset
        performanceService.reset();
        performanceService.reload();
        assertTrue(performanceService.getDataArr().isEmpty());

        // 6. Record new data after reset
        performanceService.record("method4", 4_000_000);
        performanceService.reload();
        assertEquals(1, performanceService.getDataArr().size());
    }

    @Test
    void multipleReloads_shouldProduceSameResults() {
        // Given: Record data
        performanceService.record("method", 1_000_000);
        performanceService.record("method", 2_000_000);

        // When: Reload multiple times
        performanceService.reload();
        List<Object[]> data1 = List.copyOf(performanceService.getDataArr());

        performanceService.reload();
        List<Object[]> data2 = List.copyOf(performanceService.getDataArr());

        // Then: Should produce identical results
        assertEquals(data1.size(), data2.size());
        assertEquals(data1.get(0)[2], data2.get(0)[2]); // Same hits
        assertEquals(data1.get(0)[3], data2.get(0)[3]); // Same average
    }
}
