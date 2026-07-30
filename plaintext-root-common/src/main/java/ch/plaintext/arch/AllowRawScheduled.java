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
 * Markiert eine Klasse, die bewusst rohes {@code @Scheduled} nutzen darf (Ausnahme von
 * {@code keinRohesScheduled}). Nur fuer echte Framework-/System-Waechter; normale periodische Logik
 * gehoert in {@link ch.plaintext.PlaintextCron}.
 *
 * <p><b>Warum es das gibt:</b> Die geteilte ArchUnit-Regel {@code keinRohesScheduled} (im Modul
 * {@code plaintext-root-archtests}) verbietet rohes Spring-{@code @Scheduled}, weil Zeitsteuerung
 * ueber das PlaintextCron-Framework laufen soll (Admin-UI mit Zeitplan/an-aus, per-Mandant-
 * Ausfuehrung, Laufzeit-Statistik). Wenige Framework-Infrastruktur-Klassen mit Sub-Minuten- bzw.
 * Selbst-Wartungs-Takten, die eine Cron-Expression nicht abbilden kann, sind davon ausgenommen —
 * sie tragen diese Annotation.
 *
 * <p><b>Fuer Consumer (app, iot, fwtool, schuetu):</b> Diese Annotation liegt in
 * {@code plaintext-root-common} auf dem MAIN-Classpath. Hat ein Consumer eine legitime eigene
 * {@code @Scheduled}-Klasse (echter System-Waechter, kein normaler periodischer Job), annotiert er
 * einfach diese Main-Klasse mit {@code @AllowRawScheduled} — ganz ohne eigenen Test-Code. Die
 * geteilte Regel erkennt die Ausnahme dann automatisch.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AllowRawScheduled {
}
