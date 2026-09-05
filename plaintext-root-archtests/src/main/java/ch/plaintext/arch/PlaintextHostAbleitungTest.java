/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * No address of our own may be derived from the request (Karte 1068, Karte 1069 A-01).
 *
 * <p><b>Why:</b> behind the reverse proxy {@code request.getServerName()} is whatever
 * {@code X-Forwarded-Host} says, and {@code ServletUriComponentsBuilder} builds on the same
 * headers. Until 05.09.2026 the proxy did not overwrite that header (Karte 1054); the
 * password-reset link was then built from it, and a forged header turned a genuine reset mail
 * into a phishing mail with a genuine token. The proxy line exists now — but a link that leaves
 * the house must not hang on one line in four nginx files that live only on the NAS. The one
 * source for our own address is {@code ch.plaintext.framework.EigeneAdresse} (setting
 * {@code app.ownhost}, then {@code plaintext.app.ownhost}, then the module's default), see
 * Karte 1046.
 *
 * <p><b>Known legacy cases</b> are listed by class name below and are tolerated until their
 * card (1069, A-01) moves them to {@code EigeneAdresse}. Remove a name here when you fix the
 * class; do not add names. A new class that needs the request host is asking the wrong
 * question — it needs the configured address.
 *
 * <p>Runs in every consumer (app, guild) through {@code dependenciesToScan}, so the list covers
 * all three repositories. Nested classes of a listed class are covered as well (the CalDAV and
 * CardDAV filters build their URL inside inner classes).
 */
@AnalyzeClasses(packages = "ch.plaintext", importOptions = ImportOption.DoNotIncludeTests.class)
public class PlaintextHostAbleitungTest {

    /** Class names (simple, without package) that still derive the host from the request. */
    static final Set<String> BEKANNTE_ALTFAELLE = Set.of(
            // root: documented fallback without forwarded headers, behind a configured public-base-url
            "MagicLinkService",
            // root: request wrapper that only rewrites the path part of getRequestURL()
            "PathParameterConfig",
            // app (Karte 1069 A-01)
            "CarddavKontoBackingBean",
            "CarddavSecurityConfig",
            "CaldavSecurityConfig",
            "ChallengeBackingBean",
            "ChallengeEinstellungenBackingBean",
            // guild (Karte 1069 A-01)
            "EventsSettingsBackingBean",
            "EventPublicController");

    private static final DescribedPredicate<JavaClass> ALTFALL = new DescribedPredicate<>(
            "bekannte Altfaelle der Host-Ableitung (Karte 1069 A-01)") {
        @Override
        public boolean test(JavaClass klasse) {
            String name = klasse.getName();
            for (String alt : BEKANNTE_ALTFAELLE) {
                // "." before the name matches the class itself, "$" its nested classes;
                // the name check on both sides keeps FooBar from matching Bar.
                if (name.endsWith("." + alt) || name.contains("." + alt + "$")) {
                    return true;
                }
            }
            return false;
        }
    };

    @ArchTest
    static final ArchRule keineEigeneAdresseAusDemRequest = noClasses()
            .that(DescribedPredicate.not(ALTFALL))
            .should().callMethod("jakarta.servlet.http.HttpServletRequest", "getServerName")
            .orShould().callMethod("jakarta.servlet.ServletRequest", "getServerName")
            .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.servlet.support.ServletUriComponentsBuilder")
            .because("Karte 1068: die eigene Adresse kommt aus EigeneAdresse (app.ownhost, "
                    + "plaintext.app.ownhost, Vorgabe), nie aus dem Request — hinter dem Proxy ist "
                    + "getServerName() der Wert aus X-Forwarded-Host, und ein Link, der per Mail das "
                    + "Haus verlaesst, darf nicht vom Aufrufer bestimmt werden. Altfaelle stehen in "
                    + "BEKANNTE_ALTFAELLE und werden ueber Karte 1069 abgebaut, nicht erweitert.");
}
