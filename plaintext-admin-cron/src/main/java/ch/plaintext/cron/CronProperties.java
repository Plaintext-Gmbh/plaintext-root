/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-wide defaults applied when a cron job's configuration row is created for the first
 * time. Bound to {@code plaintext.cron}.
 * <p>
 * A job may override either value through {@link ch.plaintext.PlaintextCron#isEnabledByDefault()}
 * and {@link ch.plaintext.PlaintextCron#isStartupByDefault()}; these properties decide only what
 * happens when a job expresses no preference.
 * <p>
 * Both default to true, which is the behaviour this module has always had. Setting
 * {@code default-startup: false} is recommended for applications whose jobs talk to external
 * systems — otherwise the first boot after a deployment fires every newly registered job at once.
 *
 * @since 1.480.0
 */
@Data
@ConfigurationProperties(prefix = "plaintext.cron")
public class CronProperties {

    /** Whether a newly registered job is enabled. */
    private boolean defaultEnabled = true;

    /** Whether a newly registered job runs once directly after startup. */
    private boolean defaultStartup = true;

    /**
     * Which {@link CronConfigStore} to use: {@code jpa} for the built-in table, or any other value
     * to suppress it and supply your own bean.
     */
    private String store = "jpa";
}
