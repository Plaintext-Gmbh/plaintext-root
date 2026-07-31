/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import java.util.Optional;

/**
 * Liest ein über die Seite <i>Root → Secrets</i> gepflegtes Secret <b>zur Laufzeit</b> aus, unabhängig
 * davon, in welchem Backend es liegt.
 *
 * <p>Dieses Interface existiert, weil der {@code vault:}-Präfix in Properties das nicht leisten kann.
 * Jener wird von einem {@code EnvironmentPostProcessor} beim Start aufgelöst und liest ausschliesslich
 * Vaultwarden. Für das Backend {@code LOCAL_DB} ist dieser Weg nicht nachrüstbar, und zwar aus zwei
 * unabhängigen Gründen: Beim Start gibt es weder eine verbundene Datenbank noch einen Mandanten, und
 * Secrets sind mandantengebunden. Ein {@code secret:}-Präfix analog zu {@code vault:} kann es deshalb
 * prinzipiell nicht geben — die Auflösung muss im Request-Kontext stattfinden.
 *
 * <p>Der angenehme Nebeneffekt: Eine Änderung in der Secrets-Verwaltung wirkt sofort, während ein
 * {@code vault:}-Property bis zum nächsten Neustart den alten Wert behält.
 *
 * <p><b>Nicht fürs UI.</b> Die Secret-Verwaltung ist bewusst one-way — Werte werden gesetzt, nie
 * angezeigt. Dieses Interface liefert Klartext für technische Verwender (etwa das Einsetzen von
 * Zugangsdaten in ein zum Download erzeugtes Skript) und gehört nicht in eine Anzeige.
 *
 * <p>Implementiert wird es vom {@code SecretService} in {@code plaintext-admin-secrets}. Verwender
 * sollten die Abhängigkeit <b>optional</b> halten ({@code @Autowired(required = false)}), denn nicht
 * jede Anwendung bindet das Secrets-Modul ein.
 */
public interface SecretResolver {

    /**
     * Klartext-Wert des Secrets im aktuellen Mandanten.
     *
     * @param name Name des Secrets, wie in der Secrets-Verwaltung angezeigt (z.B.
     *             {@code zeiterfassung.jira-password})
     * @return der Wert, oder {@link Optional#empty()}, wenn kein Secret dieses Namens existiert, es
     *         gelöscht ist oder das hinterlegende Backend gerade nicht erreichbar ist. Ein leeres
     *         Ergebnis ist <b>kein Fehler</b> — Aufrufer sollen darauf einen Fallback vorsehen
     *         (typischerweise die bisherige Property) statt zu scheitern.
     */
    Optional<String> resolve(String name);
}
