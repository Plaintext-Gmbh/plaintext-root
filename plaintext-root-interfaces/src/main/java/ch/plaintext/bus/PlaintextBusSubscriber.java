/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

/**
 * Abonnent eines Event-Typs auf dem internen Bus. Alle Spring-Beans dieses Typs werden vom
 * Dispatcher automatisch eingesammelt (Standard-Collection-Injection, kein Classpath-Scan nötig —
 * analog {@code PlaintextCron}/{@code CronController}, aber ohne dessen Bean-Wrapping-Zeremonie, da
 * hier kein Zustand pro Mandant im Subscriber selbst gehalten wird).
 *
 * @param <T> Typ des Events, auf den dieser Subscriber hört
 * @author info@plaintext.ch
 * @since 2026
 */
public interface PlaintextBusSubscriber<T> {

    /** Der Event-Typ, auf den dieser Subscriber hört (exakte Klasse, keine Subtyp-Erkennung). */
    Class<T> eventType();

    /**
     * Zustellungs-Scope dieses Subscribers — bestimmt, welche {@link PlaintextBusEvent#scope()}
     * zugestellt werden (siehe {@link PlaintextEventBus} für die genaue Matrix). Default
     * {@link ExecutionScope#MANDAT} (der haeufigste Fall: Business-Module reagieren pro Mandant).
     */
    default ExecutionScope scope() {
        return ExecutionScope.MANDAT;
    }

    /**
     * Wird für jedes zugestellte Event aufgerufen. Der Dispatcher hat vor dem Aufruf bereits einen
     * zum Event passenden {@code SecurityContext} gesetzt (Mandant, bei {@link ExecutionScope#PERSOENLICH}
     * auch Benutzer) — Implementierungen dürfen {@code PlaintextSecurityHolder} normal nutzen.
     *
     * @param payload das Event-Objekt
     * @param ctx     der volle Envelope (für Metadaten wie {@code at}, falls benötigt)
     */
    void onEvent(T payload, PlaintextBusEvent<T> ctx);
}
