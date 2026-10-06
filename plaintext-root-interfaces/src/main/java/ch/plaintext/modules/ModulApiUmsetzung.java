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
 * Describes how an <b>implementation</b> of a {@link ModulApi} contract behaves (card 1422, Daniel
 * 04.10.2026: "eigene MCP-Beschreibungen beim Implementieren, so dass nicht implizite Dinge trotzdem
 * klarer werden").
 *
 * <p>The interface says <i>what</i>; this annotation says <i>how this implementation does it</i>:
 * preconditions, limits, behaviour per tenant, error cases, cost — the things a caller otherwise
 * learns only by reading the code. The catalog processor writes it into the module's catalog next
 * to the contract; the module view and MCP show it per implementer.
 *
 * <p>Allowed on the class and, for single methods that need their own note, on methods. All four
 * elements are mandatory (Daniel 04.10.2026); {@code hinweise = {}} and {@code beispiele = {}} are
 * valid when there is genuinely nothing to add. A shared ArchUnit rule requires it on every
 * implementation of a {@code @ModulApi} service.
 *
 * <p>No secrets, credentials or internal host names in any element — the texts are served over
 * MCP; a contract test looks for such patterns.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ModulApiUmsetzung {

    /** How this implementation fulfils the contract, in a few sentences. */
    String beschreibung();

    /** Effects beyond returning a value. */
    Seiteneffekte seiteneffekte();

    /** Preconditions, limits, tenant behaviour, error cases — one point per entry. */
    String[] hinweise();

    /** Short usage examples (a call and what it yields); may be empty. */
    String[] beispiele();

    /** Effects beyond returning a value. */
    enum Seiteneffekte {
        /** Reads only. */
        KEINE,
        /** Writes inside the application (database, cache, files of the application). */
        INTERN,
        /** Reaches the outside world: mail, messenger, payment, a foreign API. */
        AUSSEN
    }
}
