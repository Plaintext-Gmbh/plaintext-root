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
 * Declares a type of {@code plaintext-root-common} as <b>stable API for the foreign
 * repositories</b> (app, guild, schuetu, iot, fwtool) — card 1300, finding 7 of analysis 1274.
 *
 * <p><b>What that means.</b> {@code plaintext-root-common} is the base every application stands
 * on, and in practice it is the larger API surface of root: on 23.09.2026 the five foreign
 * repositories had 595 bytecode edges into it, against 456 into the official
 * {@code plaintext-root-interfaces}. Of its 70 types, exactly the ones carrying this annotation
 * may be used from a foreign repository; every other type is root-internal and the shared rule
 * {@code PlaintextRootCommonApiTest} (in {@code plaintext-root-archtests}) reports its use.
 *
 * <p><b>For whoever changes an annotated type:</b> a change to it is a six-repository event.
 * {@code SuperModel} is the base class of practically every entity in all six repositories;
 * renaming a method of {@code PlaintextSecurityHolder} or {@code FacesMessages} breaks the
 * foreign builds on their next root bump, not this one. Add, do not change; deprecate before you
 * remove.
 *
 * <p><b>For whoever wants to use a type that does not carry it:</b> that is a decision, not an
 * oversight — either declare the type here with a reason (it was built for consumers), or add
 * a justified entry to the consumer's allowlist ({@code root-common-api}), or move what you need
 * into {@code plaintext-root-interfaces}.
 *
 * <p>Nested types count as declared if their enclosing type is. {@link RetentionPolicy#CLASS}
 * is enough: the rule reads bytecode, nothing reads this at runtime.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface StabileApi {

    /** Why the type is API for the foreign repositories — mandatory, like an allowlist justification. */
    String value();
}
