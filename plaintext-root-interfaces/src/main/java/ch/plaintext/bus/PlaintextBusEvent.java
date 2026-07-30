/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import java.time.Instant;

/**
 * Umschlag, in dem {@link PlaintextEventBus} ein typisiertes Payload (typischerweise ein
 * Model-Interface aus einem {@code *-interfaces}-Modul, z. B. {@code IRechnung},
 * {@code IncomingMail}) auf den internen Bus veröffentlicht. Der Kontext ({@code mandant}/
 * {@code userId}) wird beim Publish aus {@code PlaintextSecurityHolder} erfasst und danach IMMER
 * aus dem Envelope gelesen — nie aus dem Thread des Subscribers (der laeuft ggf. auf einem anderen
 * Thread als der Publisher).
 *
 * @param payload  das eigentliche Event-Objekt
 * @param scope    Zustellungs-Scope (siehe {@link ExecutionScope})
 * @param mandant  Mandant des Events, oder {@code null} bei {@link ExecutionScope#APPLICATION}
 * @param userId   auslösender Benutzer, nur bei {@link ExecutionScope#PERSOENLICH} gesetzt
 * @param at       Zeitpunkt des Publish
 * @param <T>      Typ des Payloads
 * @author info@plaintext.ch
 * @since 2026
 */
public record PlaintextBusEvent<T>(T payload, ExecutionScope scope, String mandant, String userId, Instant at) {
}
