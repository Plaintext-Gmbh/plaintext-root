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
     * @deprecated since Task 005 (cron scopes unified) — override {@link #getScope()} instead of
     *             {@code isGlobal()}. Kept for backwards compatibility: the default mapping in
     *             {@link #getScope()} still reads this value.
     */
    @Deprecated
    default boolean isGlobal() {
        return false;
    }

    /**
     * Unification with the internal event bus ({@code ch.plaintext.bus}): the same
     * {@link ExecutionScope} enum as bus events and subscribers. It determines how
     * {@code run(...)} is called: {@link ExecutionScope#APPLICATION}/{@link ExecutionScope#MANDAT}
     * call {@link #run(String)} (exactly once globally, or once per tenant); implementations with
     * {@link ExecutionScope#PERSOENLICH} are instead called once per active user of the tenant via
     * {@link #run(String, String)} — such implementations MUST override that overload.
     *
     * @return {@link ExecutionScope#APPLICATION} if {@link #isGlobal()}, otherwise
     *         {@link ExecutionScope#MANDAT} (default mapping; new cron jobs override
     *         {@code getScope()} directly instead)
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
     * Only relevant for {@link ExecutionScope#PERSOENLICH} — called once per active user of the
     * tenant (context: tenant AND user set). The default delegates to {@link #run(String)}
     * (ignoring {@code userId}) — implementations with scope {@code PERSOENLICH} MUST override it,
     * otherwise the logic runs identically several times, once per user and without any user
     * reference.
     *
     * @param mandant the tenant
     * @param userId  the user this run is executed for
     */
    default void run(String mandant, String userId) {
        run(mandant);
    }

    /**
     * Whether this job should be enabled when its configuration row is created for the first time.
     * <p>
     * Returning {@code null} — the default — defers to {@code plaintext.cron.default-enabled}.
     * Override it where the job itself knows better than the application-wide setting.
     *
     * @return true or false to decide, null to defer to the application default
     * @since 1.480.0
     */
    default Boolean isEnabledByDefault() {
        return null;
    }

    /**
     * Whether this job should run once directly after startup when its configuration row is
     * created for the first time.
     * <p>
     * Returning {@code null} — the default — defers to {@code plaintext.cron.default-startup}.
     * Applications whose jobs call out to external systems usually want that default set to
     * false, so that the first boot after a deployment does not fire everything at once.
     *
     * @return true or false to decide, null to defer to the application default
     * @since 1.480.0
     */
    default Boolean isStartupByDefault() {
        return null;
    }

}