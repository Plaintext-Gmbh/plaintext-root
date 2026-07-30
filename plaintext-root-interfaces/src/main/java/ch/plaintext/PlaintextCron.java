/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import ch.plaintext.bus.ExecutionScope;

/**
 * Interface for scheduled cron jobs in the Plaintext application.
 * Implementations are auto-discovered and can be managed (enabled/disabled,
 * schedule changed) via the admin cron UI. Each cron job runs per mandate
 * unless {@link #isGlobal()} returns true.
 */
public interface PlaintextCron {

    /**
     * Indicates whether this cron job runs globally (once) or per mandate.
     *
     * @return true if the job should run once globally, false if it should run per mandate
     * @deprecated seit Task 005 (Cron-Scopes vereinheitlicht) — {@link #getScope()} überschreiben
     *             statt {@code isGlobal()}. Bleibt aus Abwärtskompatibilität bestehen: das
     *             Default-Mapping in {@link #getScope()} liest diesen Wert weiterhin.
     */
    @Deprecated
    default boolean isGlobal() {
        return false;
    }

    /**
     * Vereinheitlichung mit dem internen Event-Bus ({@code ch.plaintext.bus}): dasselbe
     * {@link ExecutionScope}-Enum wie Bus-Events/-Subscriber. Bestimmt, wie {@code run(...)}
     * aufgerufen wird: {@link ExecutionScope#APPLICATION}/{@link ExecutionScope#MANDAT} rufen
     * {@link #run(String)} auf (genau einmal global bzw. einmal je Mandant); Implementierungen mit
     * {@link ExecutionScope#PERSOENLICH} werden stattdessen einmal je aktivem Benutzer des
     * Mandanten über {@link #run(String, String)} aufgerufen — solche Implementierungen MÜSSEN
     * diesen Overload überschreiben.
     *
     * @return {@link ExecutionScope#APPLICATION}, falls {@link #isGlobal()}, sonst
     *         {@link ExecutionScope#MANDAT} (Default-Mapping; neue Crons überschreiben stattdessen
     *         direkt {@code getScope()})
     */
    @SuppressWarnings("deprecation")
    default ExecutionScope getScope() {
        return isGlobal() ? ExecutionScope.APPLICATION : ExecutionScope.MANDAT;
    }

    /**
     * Returns a human-readable display name for this cron job.
     * This name will be shown in the cron jobs table.
     *
     * @return the display name (defaults to simple class name if not overridden)
     */
    default String getDisplayName() {
        return getClass().getSimpleName();
    }

    /**
     * Returns the default cron expression for this job.
     * This expression will be used when creating a new database entry for this cron.
     *
     * @return the default cron expression (defaults to "0 0 * * *" - every day at midnight)
     */
    default String getDefaultCronExpression() {
        return "0 0 * * *";
    }

    /**
     * Executes the cron job logic for the given mandate.
     *
     * @param mandant the mandate/tenant identifier for which the job runs
     */
    void run(String mandant);

    /**
     * Nur für {@link ExecutionScope#PERSOENLICH} relevant — wird einmal je aktivem Benutzer des
     * Mandanten aufgerufen (Kontext: Mandant UND Benutzer gesetzt). Default delegiert auf
     * {@link #run(String)} (ignoriert {@code userId}) — Implementierungen mit Scope
     * {@code PERSOENLICH} MÜSSEN das überschreiben, sonst läuft die Logik pro Benutzer mehrfach
     * identisch (ohne Benutzerbezug).
     *
     * @param mandant der Mandant
     * @param userId  der Benutzer, für den dieser Lauf ausgeführt wird
     */
    default void run(String mandant, String userId) {
        run(mandant);
    }

}