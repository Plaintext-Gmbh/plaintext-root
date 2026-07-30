/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.flyway;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/**
 * Flyway callback that repairs failed migrations before migration starts.
 * This is necessary for production databases that may have failed migrations
 * due to table structure changes (e.g., email_config to email_config_v2 migration).
 *
 * @author info@plaintext.ch
 * @since 2024-12-31
 */
@Component
@Slf4j
public class FlywayRepairCallback implements Callback {

    @Override
    public boolean supports(Event event, Context context) {
        // Run before migrations start
        return event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        // Repair cannot run in a transaction
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        log.info("Running Flyway repair to fix any failed migrations...");
        try {
            // Delete all failed migrations (success=0) from history
            // These migrations failed because they were trying to modify tables
            // that were already updated by Hibernate or Java migrations
            int deletedCount = context.getConnection().createStatement().executeUpdate(
                "DELETE FROM flyway_schema_history WHERE NOT success"
            );
            if (deletedCount > 0) {
                log.info("Flyway repair completed - removed {} failed migration(s)", deletedCount);
            }
        } catch (Exception e) {
            log.warn("Flyway repair encountered an issue (this may be normal): {}", e.getMessage());
            // Don't throw - allow migration to proceed
        }
    }

    @Override
    public String getCallbackName() {
        return "FlywayRepairCallback";
    }
}
