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
 * Beweis-/Muster-Cron für {@link ExecutionScope#PERSOENLICH} (Task 005): loggt je aktivem Benutzer
 * des Mandanten Mandant+Benutzer. Dient als Muster + Integrationstest für die PERSOENLICH-Iteration
 * im Framework ({@link SuperCron#run()}) — keine echte PERSOENLICH-Umstellung bestehender Crons ist
 * Teil dieses Tasks (eigene Entscheidung je Cron).
 *
 * <p>Default-Cron-Ausdruck bewusst selten ({@code 1 1 1 1 1} — nächster 1. Januar, 01:01), damit der
 * Job im Normalbetrieb praktisch nie periodisch feuert. Die Config-Zeile wird beim erstmaligen
 * Anlegen wie jeder neue Cron mit {@code startup=true} erstellt (Framework-Standard, siehe
 * {@code CronController.createCronConfigEntity}) — feuert also einmalig beim ersten Deploy, was
 * für einen Beweis-Cron erwünscht ist (sofortige, sichtbare Bestätigung im Log, dass die
 * PERSOENLICH-Iteration funktioniert).</p>
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
        // PERSOENLICH-Crons erhalten normalerweise run(mandant, userId) je Benutzer; dieser Pfad
        // wird nur erreicht, wenn ihn jemand direkt aufruft (z. B. Alt-Code) -- rein defensiv geloggt.
        log.warn("DemoPersonalCron.run(String) direkt aufgerufen (mandant={}) — erwartet wurde "
                + "run(mandant, userId) je Benutzer", mandant);
    }

    @Override
    public void run(String mandant, String userId) {
        log.info("DemoPersonalCron: mandant={} user={}", mandant, userId);
    }
}
