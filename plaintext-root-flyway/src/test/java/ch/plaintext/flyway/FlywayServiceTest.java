/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status report 29.08.2026, measure 13 (JaCoCo gate): the module did not have a single test.
 * The service reads {@code flyway_schema_history} via JDBC, bypassing Hibernate, and deletes
 * entries — both are verifiable without a database because the JDBC objects are mocked.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FlywayService")
class FlywayServiceTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet resultSet;
    @Mock private Flyway flyway;
    @Mock private MigrationInfoService infoService;

    private FlywayService service;

    @BeforeEach
    void setUp() throws SQLException {
        service = new FlywayService();
        service.setDataSource(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(flyway.info()).thenReturn(infoService);
    }

    @Test
    void initWarntNurOhneFlyway() {
        ReflectionTestUtils.invokeMethod(service, "init");
        service.setFlyway(flyway);
        ReflectionTestUtils.invokeMethod(service, "init");
        assertSame(flyway, service.getFlyway());
    }

    @Test
    void infoOhneFlywayIstLeerStattNull() {
        assertEquals(0, service.info().length);
        assertNull(service.infoCurrent());
    }

    @Test
    void infoDelegiertAnFlyway() {
        MigrationInfo aktuell = org.mockito.Mockito.mock(MigrationInfo.class);
        when(infoService.all()).thenReturn(new MigrationInfo[] {aktuell});
        when(infoService.current()).thenReturn(aktuell);
        service.setFlyway(flyway);

        assertEquals(1, service.info().length);
        assertSame(aktuell, service.infoCurrent());
    }

    @Test
    void infoFromDatabaseLiestDieHistorieZeileFuerZeile() throws SQLException {
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getInt("installed_rank")).thenReturn(7);
        when(resultSet.getString("version")).thenReturn("1774038471");
        when(resultSet.getString("description")).thenReturn("create branding logo table");
        when(resultSet.getString("type")).thenReturn("SQL");
        when(resultSet.getString("script")).thenReturn("V1774038471__create_branding_logo_table.sql");
        when(resultSet.getInt("checksum")).thenReturn(-12345);
        when(resultSet.getString("installed_by")).thenReturn("plaintext");
        Timestamp zeit = Timestamp.valueOf("2026-08-29 10:00:00");
        when(resultSet.getTimestamp("installed_on")).thenReturn(zeit);
        when(resultSet.getInt("execution_time")).thenReturn(42);
        when(resultSet.getBoolean("success")).thenReturn(true);

        List<FlywaySchemaHistory> historie = service.loadAllHistory();

        assertEquals(1, historie.size());
        FlywaySchemaHistory h = historie.getFirst();
        assertEquals(7, h.getInstalledRank());
        assertEquals("1774038471", h.getVersion());
        assertEquals("create branding logo table", h.getDescription());
        assertEquals("SQL", h.getType());
        assertEquals("V1774038471__create_branding_logo_table.sql", h.getScript());
        assertEquals(-12345, h.getChecksum());
        assertEquals("plaintext", h.getInstalledBy());
        assertEquals(zeit, h.getInstalledOn());
        assertEquals(42, h.getExecutionTime());
        assertEquals(Boolean.TRUE, h.getSuccess());
        verify(statement).execute("CHECKPOINT");
    }

    @Test
    void fehlgeschlagenerCheckpointIstKeinFehler() throws SQLException {
        when(statement.execute("CHECKPOINT")).thenThrow(new SQLException("nicht erlaubt"));
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(service.infoFromDatabase().isEmpty());
        verify(statement).executeQuery(anyString());
    }

    @Test
    void infoFromDatabaseOhneDataSourceOderMitDbFehlerIstLeer() throws SQLException {
        service.setDataSource(null);
        assertTrue(service.infoFromDatabase().isEmpty());

        service.setDataSource(dataSource);
        when(dataSource.getConnection()).thenThrow(new SQLException("DB weg"));
        assertTrue(service.infoFromDatabase().isEmpty());
    }

    @Test
    void deleteHistoryEntryBindetDenRangAlsParameter() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        service.deleteHistoryEntry(7);

        verify(connection).prepareStatement("DELETE FROM \"flyway_schema_history\" WHERE \"installed_rank\" = ?");
        verify(preparedStatement).setInt(1, 7);
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void deleteHistoryEntryOhneTrefferOderOhneDataSourceOderMitFehler() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(0);
        service.deleteHistoryEntry(99);
        verify(preparedStatement).setInt(1, 99);

        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("gesperrt"));
        service.deleteHistoryEntry(100);

        service.setDataSource(null);
        service.deleteHistoryEntry(1);
        verify(preparedStatement, never()).setInt(1, 1);
    }
}
