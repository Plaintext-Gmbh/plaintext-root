/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.flyway;

import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for FlywayRepairCallback - Flyway migration repair.
 */
@ExtendWith(MockitoExtension.class)
class FlywayRepairCallbackTest {

    @Mock
    private Context context;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    private FlywayRepairCallback callback;

    @BeforeEach
    void setUp() throws Exception {
        callback = new FlywayRepairCallback();
    }

    @Test
    void supports_shouldReturnTrue_forBeforeMigrate() {
        assertTrue(callback.supports(Event.BEFORE_MIGRATE, context));
    }

    @Test
    void supports_shouldReturnFalse_forOtherEvents() {
        assertFalse(callback.supports(Event.AFTER_MIGRATE, context));
        assertFalse(callback.supports(Event.BEFORE_CLEAN, context));
        assertFalse(callback.supports(Event.AFTER_EACH_MIGRATE, context));
    }

    @Test
    void canHandleInTransaction_shouldReturnFalse() {
        assertFalse(callback.canHandleInTransaction(Event.BEFORE_MIGRATE, context));
    }

    @Test
    void handle_shouldDeleteFailedMigrations() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(anyString())).thenReturn(2);

        callback.handle(Event.BEFORE_MIGRATE, context);

        verify(statement).executeUpdate("DELETE FROM flyway_schema_history WHERE NOT success");
    }

    @Test
    void handle_shouldNotThrow_onSQLException() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(anyString())).thenThrow(new RuntimeException("Table not found"));

        assertDoesNotThrow(() -> callback.handle(Event.BEFORE_MIGRATE, context));
    }

    @Test
    void handle_shouldHandleZeroDeletedRows() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(anyString())).thenReturn(0);

        assertDoesNotThrow(() -> callback.handle(Event.BEFORE_MIGRATE, context));
    }

    @Test
    void getCallbackName_shouldReturnCorrectName() {
        assertEquals("FlywayRepairCallback", callback.getCallbackName());
    }
}
