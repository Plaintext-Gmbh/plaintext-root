/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import ch.plaintext.bus.ExecutionScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Proof-of-concept / sample cron for {@link ExecutionScope#PERSOENLICH} (Task 005): logs tenant +
 * user for every active user of the tenant. Serves as a sample and as an integration test for the
 * PERSOENLICH iteration in the framework ({@link SuperCron#run()}) — actually converting existing
 * crons to PERSOENLICH is not part of this task (that is decided per cron).
 *
 * <p>The default cron expression is deliberately rare ({@code 1 1 1 1 1} — next 1 January, 01:01), so
 * that the job practically never fires periodically in normal operation. When first created, the
 * configuration row is created with {@code startup=true} like every new cron (framework default, see
 * {@code CronController.createCronConfigEntity}) — so it fires once on the first deployment, which is
 * desirable for a proof-of-concept cron (immediate, visible confirmation in the log that the
 * PERSOENLICH iteration works).</p>
 */
@Component
@Scope("prototype")
@Slf4j
public class DemoPersonalCron implements PlaintextCron {

    @Override
    public ExecutionScope getScope() {
        return ExecutionScope.PERSOENLICH;
    }

    @Override
    public String getDisplayName() {
        return "Demo: PERSOENLICH-Scope (je Benutzer)";
    }

    @Override
    public String getDefaultCronExpression() {
        return "1 1 1 1 1";
    }

    @Override
    public void run(String mandant) {
        // PERSOENLICH crons normally receive run(mandant, userId) per user; this path is only
        // reached when someone calls it directly (e.g. legacy code) -- logged purely defensively.
        log.warn("DemoPersonalCron.run(String) direkt aufgerufen (mandant={}) — erwartet wurde "
                + "run(mandant, userId) je Benutzer", mandant);
    }

    @Override
    public void run(String mandant, String userId) {
        log.info("DemoPersonalCron: mandant={} user={}", mandant, userId);
    }
}
