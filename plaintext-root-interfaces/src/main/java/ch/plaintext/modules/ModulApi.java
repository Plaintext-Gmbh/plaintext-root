/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a <b>module API</b>: a contract other modules may use (card 1422, Daniel
 * 04.10.2026). Annotated interfaces are picked up by the catalog processor
 * {@code plaintext-root-katalog} in <b>every</b> module, not only in {@code *-interfaces} modules,
 * and appear in the root module view and over MCP ({@code list_modul_schnittstellen},
 * {@code get_modul_schnittstelle}, {@code analysiere_module}).
 *
 * <p>The purpose is taken from the Javadoc, as before — it is mandatory, the processor rejects an
 * annotated interface without it. The annotation itself only adds what the Javadoc cannot say in a
 * machine-readable way:
 * <ul>
 *   <li>{@link #art()}: a service other modules call ({@link Art#SCHNITTSTELLE}) or a model handed
 *       between modules ({@link Art#DTO}). A DTO's simple name starts with {@code I}
 *       ({@code IZeiteintrag}); a shared ArchUnit rule enforces that.</li>
 *   <li>{@link #stabilitaet()}: whether a consumer may build on it ({@link Stabilitaet#STABIL}), it
 *       is still moving ({@link Stabilitaet#NEU}, the default) or it is on its way out
 *       ({@link Stabilitaet#VERALTET}, then {@link #ersatz()} names the successor).</li>
 *   <li>{@link #seit()}: the version that introduced it, e.g. {@code 1.749.0}.</li>
 * </ul>
 *
 * <p>How an implementation behaves beyond the contract — preconditions, limits, side effects —
 * belongs on the implementing class: {@link ModulApiUmsetzung}.
 *
 * <p>{@link RetentionPolicy#RUNTIME}: the runtime analysis reads it from the loaded types as well.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModulApi {

    /** Service contract or model handed between modules. */
    Art art();

    /** How far a consumer may rely on it; {@link Stabilitaet#NEU} until declared otherwise. */
    Stabilitaet stabilitaet() default Stabilitaet.NEU;

    /** Version that introduced it, e.g. {@code 1.749.0}; empty if unknown. */
    String seit() default "";

    /** For {@link Stabilitaet#VERALTET}: the successor (fully qualified name or a short hint). */
    String ersatz() default "";

    /** Kind of module API. */
    enum Art {
        /** A service other modules call (e.g. a mail sender, a photo source). */
        SCHNITTSTELLE,
        /** A model handed between modules; its simple name starts with {@code I}. */
        DTO
    }

    /** How far a consumer may rely on the API. */
    enum Stabilitaet {
        /** Changes only additively; removals go through {@link #VERALTET} first. */
        STABIL,
        /** Still moving; may change with a release note. */
        NEU,
        /** On its way out; {@link ModulApi#ersatz()} names the successor. */
        VERALTET
    }
}
