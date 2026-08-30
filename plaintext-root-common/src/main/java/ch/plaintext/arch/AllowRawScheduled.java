/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that is deliberately allowed to use raw {@code @Scheduled} (an exception to
 * {@code keinRohesScheduled}). Only for genuine framework/system guards; ordinary periodic logic
 * belongs in {@link ch.plaintext.PlaintextCron}.
 *
 * <p><b>Why this exists:</b> the shared ArchUnit rule {@code keinRohesScheduled} (in the module
 * {@code plaintext-root-archtests}) forbids raw Spring {@code @Scheduled}, because scheduling is
 * meant to run through the PlaintextCron framework (admin UI with schedule and on/off switch,
 * per-tenant execution, runtime statistics). A handful of framework infrastructure classes with
 * sub-minute or self-maintenance intervals that a cron expression cannot express are exempt —
 * they carry this annotation.
 *
 * <p><b>For consumers (app, iot, fwtool, schuetu):</b> this annotation lives in
 * {@code plaintext-root-common} on the MAIN classpath. If a consumer has a legitimate
 * {@code @Scheduled} class of its own (a real system guard, not an ordinary periodic job), it
 * simply annotates that main class with {@code @AllowRawScheduled} — with no test code of its
 * own. The shared rule then recognises the exception automatically.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AllowRawScheduled {
}
