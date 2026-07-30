/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.flyway;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

/**
 * Service for managing Flyway schema history
 *
 * @author plaintext.ch
 * @since 1.108.0
 */
@Component
@Data
@Slf4j
public class FlywayService {

    @Autowired
    DataSource dataSource;

    @Autowired(required = false)
    private Flyway flyway;

    @PostConstruct
    private void init() {
        if (flyway == null) {
            log.warn("flyway is null");
            return;
        }
    }



    public List<FlywaySchemaHistory> loadAllHistory() {
        return infoFromDatabase();
    }

    public MigrationInfo[] info() {
        if (flyway == null) {
            log.warn("flyway is null - returning empty array");
            return new MigrationInfo[0];
        }
        return flyway.info().all();
    }

    /**
     * Get all Flyway schema history entries directly from database
     * This works even when Flyway is disabled
     * Uses native SQL to bypass Hibernate cache and always get fresh data
     */
    public List<FlywaySchemaHistory> infoFromDatabase() {
        if (dataSource == null) {
            log.warn("DataSource is null - returning empty list");
            return List.of();
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Force database to write pending changes to disk
            try {
                stmt.execute("CHECKPOINT");
            } catch (Exception e) {
                log.debug("CHECKPOINT failed (might not be needed): {}", e.getMessage());
            }

            var rs = stmt.executeQuery(
                "SELECT \"installed_rank\", \"version\", \"description\", \"type\", \"script\", " +
                "\"checksum\", \"installed_by\", \"installed_on\", \"execution_time\", \"success\" " +
                "FROM \"flyway_schema_history\" " +
                "ORDER BY \"installed_rank\""
            );

            List<FlywaySchemaHistory> result = new java.util.ArrayList<>();

            while (rs.next()) {
                FlywaySchemaHistory entry = new FlywaySchemaHistory();
                entry.setInstalledRank(rs.getInt("installed_rank"));
                entry.setVersion(rs.getString("version"));
                entry.setDescription(rs.getString("description"));
                entry.setType(rs.getString("type"));
                entry.setScript(rs.getString("script"));
                entry.setChecksum(rs.getInt("checksum"));
                entry.setInstalledBy(rs.getString("installed_by"));
                entry.setInstalledOn(rs.getTimestamp("installed_on"));
                entry.setExecutionTime(rs.getInt("execution_time"));
                entry.setSuccess(rs.getBoolean("success"));
                result.add(entry);
            }

            return result;

        } catch (Exception e) {
            log.error("Error reading Flyway history from database: " + e.getMessage(), e);
            return List.of();
        }
    }

    public MigrationInfo infoCurrent() {
        if (flyway == null) {
            log.warn("flyway is null");
            return null;
        }
        return flyway.info().current();
    }


    /**
     * Delete a specific Flyway schema history entry by installed_rank using direct JDBC
     *
     * @param installedRank the installed rank of the entry to delete
     */
    public void deleteHistoryEntry(Integer installedRank) {
        if (dataSource == null) {
            log.warn("DataSource is null");
            return;
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            int rowsDeleted = stmt.executeUpdate(
                "DELETE FROM \"flyway_schema_history\" WHERE \"installed_rank\" = " + installedRank
            );

            if (rowsDeleted > 0) {
                log.info("Deleted Flyway schema history entry with installed_rank: {}", installedRank);
            } else {
                log.warn("No entry found with installed_rank: {}", installedRank);
            }

        } catch (Exception e) {
            log.error("Error deleting Flyway schema history entry with installed_rank: " + installedRank, e);
        }
    }

}
