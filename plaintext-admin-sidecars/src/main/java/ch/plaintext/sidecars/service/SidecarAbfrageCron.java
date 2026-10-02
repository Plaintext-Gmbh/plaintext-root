/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.PlaintextCron;
import ch.plaintext.bus.ExecutionScope;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Fragt alle Sidecars alle fünf Minuten ab (Karte 1400), damit die Registry auch ohne offene
 * Übersicht weiss, wer erreichbar ist. Instanzweit, einmal je Lauf und nicht je Mandant.
 */
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class SidecarAbfrageCron implements PlaintextCron {

    private final SidecarService service;

    @Override
    public String getDisplayName() {
        return "Sidecars: Zustand abfragen";
    }

    @Override
    public String getDefaultCronExpression() {
        return "*/5 * * * *";
    }

    @Override
    public ExecutionScope getScope() {
        return ExecutionScope.APPLICATION;
    }

    @Override
    public void run(String mandant) {
        service.aktualisiereAlle();
    }
}
