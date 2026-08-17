/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.boot.menu.MenuItemImpl;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static ch.plaintext.boot.security.PageAccessGuardTestFactory.eigenschaften;
import static ch.plaintext.boot.security.PageAccessGuardTestFactory.guard;
import static ch.plaintext.boot.security.PageAccessGuardTestFactory.guardMitFehler;
import static ch.plaintext.boot.security.PageAccessGuardTestFactory.menu;
import static ch.plaintext.boot.security.PageAccessGuardTestFactory.reportMitMenues;
import static ch.plaintext.boot.security.PageAccessGuardTestFactory.strictMitMenues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests fuer den Seiten-Zugriffsschutz (Karte 308).
 *
 * <p>Die Tests arbeiten mit echten {@link MenuItemImpl}-Objekten und einem echten
 * {@code SecurityProvider}-Stub, damit die Rollen- und Eltern-Logik von {@code isOn()} mitgeprueft
 * wird (siehe {@link PageAccessGuardTestFactory}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageAccessGuardServiceTest {

    private static final Set<String> NUR_USER = Set.of("user");
    private static final Set<String> ROOT_USER = Set.of("user", "root");
    private static final Set<String> ADMIN_USER = Set.of("user", "admin");

    @Mock
    private FacesContext facesContext;

    @Mock
    private ExternalContext externalContext;

    // ============================================================ Grundverhalten

    @Test
    void nullUndLeereViewIdWerdenErlaubt() {
        PageAccessGuardService service = strictMitMenues();
        assertTrue(service.hasAccessToView(null));
        assertTrue(service.hasAccessToView(""));
        assertTrue(service.hasAccessToView("   "));
    }

    @Test
    void systemseitenSindImmerErreichbar() {
        PageAccessGuardService service = strictMitMenues();
        assertTrue(service.hasAccessToView("/home.xhtml"));
        assertTrue(service.hasAccessToView("/index.xhtml"));
        assertTrue(service.hasAccessToView("/access-denied.xhtml"));
        assertTrue(service.hasAccessToView("/error.xhtml"));
        assertTrue(service.hasAccessToView("/login.xhtml"));
        // gleiche Seite, andere Endung / Schreibweise
        assertTrue(service.hasAccessToView("/index.html"));
        assertTrue(service.hasAccessToView("/INDEX.XHTML"));
    }

    @Test
    @DisplayName("Framework-Allowlist: login-totp, myuser und useradmin bleiben ohne Menue erreichbar")
    void frameworkAllowlistIstErreichbar() {
        PageAccessGuardService service = strictMitMenues();
        // Zweiter TOTP-Schritt: der User ist hier noch nicht voll authentifiziert.
        assertTrue(service.hasAccessToView("/login-totp.xhtml"));
        // Eigenes Profil: in der Topbar fuer jeden User verlinkt.
        assertTrue(service.hasAccessToView("/myuser.xhtml"));
        // Benutzerverwaltung: eigenes Gate + harter requestMatcher in PlaintextSecurityConfig.
        assertTrue(service.hasAccessToView("/useradmin.xhtml"));
    }

    @Test
    @DisplayName("nosec/** bleibt offen — sonst wuerde der Filter die Anzeige-Views der Consumer sperren")
    void nosecPraefixIstErreichbar() {
        PageAccessGuardService service = strictMitMenues();
        assertTrue(service.hasAccessToView("/nosec/uhr.xhtml"));
        assertTrue(service.hasAccessToView("/nosec/tabelle/tabelle.xhtml"));
    }

    @Test
    void notAusSchalterErlaubtAlles() {
        PageAccessGuardService service = guard(PageGuardMode.STRICT, false);
        assertFalse(service.isEnabled());
        assertTrue(service.hasAccessToView("/mandatemenu.xhtml"));
        assertTrue(service.hasAccessToView("/voellig-unbekannt.xhtml"));
    }

    @Test
    void modusWirdAusDenPropertiesUebernommen() {
        assertEquals(PageGuardMode.STRICT, strictMitMenues().getMode());
        assertEquals(PageGuardMode.REPORT, reportMitMenues().getMode());
        assertEquals(PageGuardMode.REPORT, new PageGuardProperties().getMode(),
                "Framework-Default muss REPORT bleiben, sonst sperren die Consumer-Apps beim Update aus");
    }

    // ============================================================ Menue-Treffer

    @Test
    void sichtbaresMenueErlaubtZugriff() {
        assertTrue(strictMitMenues(menu("kontakte.html", true)).hasAccessToView("/kontakte.xhtml"));
    }

    @Test
    void unsichtbaresMenueVerweigertZugriff() {
        assertFalse(strictMitMenues(menu("kontakte.html", false)).hasAccessToView("/kontakte.xhtml"));
    }

    @Test
    @DisplayName("Mehrere Menuepunkte auf denselben Link: ein sichtbarer genuegt (vorher entschied der erste Treffer)")
    void beiMehrerenMenuepunktenGenuegtEinSichtbarer() {
        PageAccessGuardService service = strictMitMenues(
                menu("dashboard.html", false),
                menu("dashboard.html", true));
        assertTrue(service.hasAccessToView("/dashboard.xhtml"));
    }

    @Test
    @DisplayName("Menuepunkte ohne Link (Container-Menues, link=\"\") gelten nie als Treffer")
    void leererMenueLinkMatchtNichts() {
        assertFalse(strictMitMenues(menu("", true)).hasAccessToView("/irgendeine-seite.xhtml"));
    }

    // ============================================================ H1/H5: kanonischer Vergleich

    @Test
    @DisplayName("H1: link=\"mandatemenu.html\" schuetzt die ROOT-Menuesteuerung jetzt wirklich")
    void h1MandatemenuIstFuerNormaleUserGesperrt() {
        MenuItemImpl root = menu("Root", "", "index.html", NUR_USER, "ROOT");
        MenuItemImpl mandatemenu = menu("Menüsteuerung", "Root", "mandatemenu.html", NUR_USER, "ROOT");
        PageAccessGuardService service = strictMitMenues(root, mandatemenu);

        assertFalse(service.hasAccessToView("/mandatemenu.xhtml"), "USER darf die ROOT-Menuesteuerung nicht sehen");
        assertFalse(service.hasAccessToView("/mandatemenu.html"), "auch nicht ueber die .html-URL");
        assertFalse(service.hasAccessToView("/MandateMenu.xhtml"), "und nicht ueber andere Gross-/Kleinschreibung");
    }

    @Test
    void h1MandatemenuBleibtFuerRootErreichbar() {
        MenuItemImpl root = menu("Root", "", "index.html", ROOT_USER, "ROOT");
        MenuItemImpl mandatemenu = menu("Menüsteuerung", "Root", "mandatemenu.html", ROOT_USER, "ROOT");
        PageAccessGuardService service = strictMitMenues(root, mandatemenu);

        assertTrue(service.hasAccessToView("/mandatemenu.xhtml"));
        assertTrue(service.hasAccessToView("/mandatemenu.html"));
    }

    @Test
    @DisplayName("H5: .htm-Links (plaintext-schuetu, plaintext-fwtool) matchen jetzt")
    void htmLinksWerdenErkannt() {
        assertFalse(strictMitMenues(menu("dashboard.htm", false)).hasAccessToView("/dashboard.xhtml"),
                "Vorher fand der Vergleich (nur .xhtml -> .html) keinen Treffer und der Guard erlaubte");
        assertTrue(strictMitMenues(menu("dashboard.htm", true)).hasAccessToView("/dashboard.xhtml"));
    }

    @Test
    @DisplayName("H5: .xhtml-Links (plaintext-app: korrespondenz, gearPackingList) matchen jetzt")
    void xhtmlLinksWerdenErkannt() {
        PageAccessGuardService service = strictMitMenues(menu("korrespondenz.xhtml", false));
        assertFalse(service.hasAccessToView("/korrespondenz.xhtml"));
        assertFalse(service.hasAccessToView("/korrespondenz.html"));
    }

    @Test
    void linksMitFuehrendemSlashUndQueryStringWerdenNormalisiert() {
        PageAccessGuardService service = strictMitMenues(menu("/kontakte.html", true));
        assertTrue(service.hasAccessToView("/kontakte.xhtml"));
        assertTrue(service.hasAccessToView("/kontakte.xhtml?id=4711"));
    }

    @Test
    void kanonischSchneidetAlleViewEndungenAb() {
        assertEquals("kontakte", PageAccessGuardService.kanonisch("/kontakte.xhtml"));
        assertEquals("kontakte", PageAccessGuardService.kanonisch("kontakte.html"));
        assertEquals("kontakte", PageAccessGuardService.kanonisch("kontakte.htm"));
        assertEquals("kontakte", PageAccessGuardService.kanonisch("kontakte.jsf"));
        assertEquals("kontakte", PageAccessGuardService.kanonisch("KONTAKTE.HTML"));
        assertEquals("unter/seite", PageAccessGuardService.kanonisch("/unter/seite.xhtml"));
        assertEquals("swagger-ui/index", PageAccessGuardService.kanonisch("swagger-ui/index.html"));
        assertEquals("", PageAccessGuardService.kanonisch(null));
        assertEquals("", PageAccessGuardService.kanonisch("  "));
    }

    // ============================================================ H2: fail-closed

    @Nested
    @DisplayName("H2: View ohne Menuezuordnung")
    class OhneMenueZuordnung {

        @Test
        void wirdImStrictModusVerweigert() {
            assertFalse(strictMitMenues().hasAccessToView("/seite-ohne-menue.xhtml"));
        }

        @Test
        void wirdImReportModusMitWarnungErlaubt() {
            assertTrue(reportMitMenues().hasAccessToView("/seite-ohne-menue.xhtml"),
                    "REPORT existiert genau dafuer: Consumer-Apps sollen nicht schlagartig aussperren");
        }
    }

    @Nested
    @DisplayName("Exception bei der Pruefung")
    class BeiException {

        @Test
        void wirdImStrictModusVerweigert() {
            assertFalse(guardMitFehler(PageGuardMode.STRICT).hasAccessToView("/kontakte.xhtml"));
        }

        @Test
        void wirdAuchImReportModusVerweigert() {
            assertFalse(guardMitFehler(PageGuardMode.REPORT).hasAccessToView("/kontakte.xhtml"),
                    "Vorher lieferte der catch-Block 'return true' — ein stiller Autorisierungs-Bypass");
        }
    }

    // ============================================================ H4: Eltern-Rollen

    @Nested
    @DisplayName("H4: Eltern-Rollen")
    class ElternRollen {

        /** „Root" (roles=ROOT) mit einem Kind ohne eigene roles — der Fall settings.html. */
        private PageAccessGuardService guardFuer(Set<String> benutzerRollen, PageGuardMode mode) {
            MenuItemImpl root = menu("Root", "", "index.html", benutzerRollen, "ROOT");
            MenuItemImpl settings = menu("Settings", "Root", "settings.html", benutzerRollen);
            return guard(mode, true, root, settings);
        }

        @Test
        void kindOhneEigeneRollenErbtDieRolleDesElternmenues() {
            assertFalse(guardFuer(NUR_USER, PageGuardMode.STRICT).hasAccessToView("/settings.xhtml"),
                    "settings.html hing unter dem ROOT-Menue, hatte aber keine eigenen roles "
                            + "-> war per Direkt-URL fuer jeden USER offen");
        }

        @Test
        void rootDarfWeiterhinZugreifen() {
            assertTrue(guardFuer(ROOT_USER, PageGuardMode.STRICT).hasAccessToView("/settings.xhtml"));
        }

        @Test
        void imReportModusGiltDieVererbungNichtUndDasVerhaltenBleibtWieBisher() {
            assertTrue(guardFuer(NUR_USER, PageGuardMode.REPORT).hasAccessToView("/settings.xhtml"),
                    "REPORT darf das Verhalten der Consumer-Apps nicht aendern");
        }

        @Test
        void mehrstufigeHierarchieWirdKomplettGeprueft() {
            MenuItemImpl root = menu("Root", "", "index.html", NUR_USER, "ROOT");
            MenuItemImpl zwischen = menu("Zwischen", "Root", "", NUR_USER);
            MenuItemImpl blatt = menu("Blatt", "Zwischen", "blatt.html", NUR_USER);
            assertFalse(guard(PageGuardMode.STRICT, true, root, zwischen, blatt).hasAccessToView("/blatt.xhtml"));
        }

        @Test
        void unbekanntesElternmenueSperrtNichtAus() {
            MenuItemImpl waise = menu("Waise", "Gibt-Es-Nicht", "waise.html", NUR_USER);
            assertTrue(guard(PageGuardMode.STRICT, true, waise).hasAccessToView("/waise.xhtml"));
        }

        @Test
        void zyklischeHierarchieFuehrtNichtZurEndlosschleife() {
            MenuItemImpl a = menu("A", "B", "a.html", NUR_USER);
            MenuItemImpl b = menu("B", "A", "b.html", NUR_USER);
            assertTrue(guard(PageGuardMode.STRICT, true, a, b).hasAccessToView("/a.xhtml"));
        }

        @Test
        @DisplayName("notifications.html hat explizite roles und bleibt fuer normale User erreichbar (Topbar-Glocke)")
        void expliziteRollenStoppenDieVererbung() {
            MenuItemImpl root = menu("Root", "", "index.html", NUR_USER, "ROOT");
            MenuItemImpl notifications = menu("Benachrichtigungen", "Root", "notifications.html",
                    NUR_USER, "USER", "ADMIN", "ROOT");
            assertTrue(guard(PageGuardMode.STRICT, true, root, notifications).hasAccessToView("/notifications.xhtml"));
        }
    }

    // ============================================================ Aliase

    @Nested
    @DisplayName("View-Aliase (Detailseiten ohne Menueeintrag)")
    class Aliase {

        private PageAccessGuardService guardFuer(Set<String> benutzerRollen) {
            MenuItemImpl root = menu("Root", "", "index.html", benutzerRollen, "ROOT");
            MenuItemImpl mandatemenu = menu("Menüsteuerung", "Root", "mandatemenu.html", benutzerRollen, "ROOT");
            return guard(PageGuardMode.STRICT, true, root, mandatemenu);
        }

        @Test
        void detailseiteErbtDieRegelnDerListenseite() {
            assertFalse(guardFuer(NUR_USER).hasAccessToView("/mandatemenudetail.xhtml"),
                    "mandatemenudetail.xhtml hatte gar keinen Menueeintrag und war voellig ungeschuetzt");
            assertTrue(guardFuer(ROOT_USER).hasAccessToView("/mandatemenudetail.xhtml"));
        }

        @Test
        void anforderungsUndHowtoDetailseitenSindAbgedeckt() {
            MenuItemImpl anforderungenAdmin = menu("Liste", "", "anforderungen.html", ADMIN_USER, "ADMIN", "ROOT");
            MenuItemImpl howtosAdmin = menu("Howtos", "", "howtos.html", ADMIN_USER, "ADMIN", "ROOT");
            PageAccessGuardService alsAdmin = guard(PageGuardMode.STRICT, true, anforderungenAdmin, howtosAdmin);
            assertTrue(alsAdmin.hasAccessToView("/anforderungdetail.xhtml"));
            assertTrue(alsAdmin.hasAccessToView("/claudesummary.xhtml"));
            assertTrue(alsAdmin.hasAccessToView("/howtodetail.xhtml"));

            MenuItemImpl anforderungenUser = menu("Liste", "", "anforderungen.html", NUR_USER, "ADMIN", "ROOT");
            MenuItemImpl howtosUser = menu("Howtos", "", "howtos.html", NUR_USER, "ADMIN", "ROOT");
            PageAccessGuardService alsUser = guard(PageGuardMode.STRICT, true, anforderungenUser, howtosUser);
            assertFalse(alsUser.hasAccessToView("/anforderungdetail.xhtml"));
            assertFalse(alsUser.hasAccessToView("/claudesummary.xhtml"));
            assertFalse(alsUser.hasAccessToView("/howtodetail.xhtml"));
        }

        @Test
        void konfigurierteAliaseWerdenAusgewertet() {
            PageGuardProperties properties = eigenschaften(PageGuardMode.STRICT, true);
            properties.getAliases().put("rechnungdetail.xhtml", "rechnungen.html");

            assertTrue(guard(properties, menu("rechnungen.html", true)).hasAccessToView("/rechnungdetail.xhtml"));
            assertFalse(guard(properties, menu("rechnungen.html", false)).hasAccessToView("/rechnungdetail.xhtml"));
        }
    }

    // ============================================================ konfigurierte Allowlist

    @Test
    void konfigurierteAllowlistErlaubtEinzelneViewsUndPraefixe() {
        PageGuardProperties properties = eigenschaften(PageGuardMode.STRICT, true);
        properties.getAllowlist().add("wander-druck.xhtml");
        properties.getAllowlist().add("public/**");

        PageAccessGuardService service = guard(properties);
        assertTrue(service.hasAccessToView("/wander-druck.xhtml"));
        assertTrue(service.hasAccessToView("/wander-druck.html"));
        assertTrue(service.hasAccessToView("/public/irgendwas.xhtml"));
        assertFalse(service.hasAccessToView("/nicht-erlaubt.xhtml"));
    }

    // ============================================================ Hilfsmethoden fuer den Report

    @Test
    void istZugeordnetErkenntSystemseitenAllowlisteAliaseUndMenues() {
        PageAccessGuardService service = strictMitMenues(menu("kontakte.html", true), menu("mandatemenu.html", true));
        assertTrue(service.istZugeordnet("/index.xhtml"));
        assertTrue(service.istZugeordnet("/myuser.xhtml"));
        assertTrue(service.istZugeordnet("/nosec/uhr.xhtml"));
        assertTrue(service.istZugeordnet("/kontakte.xhtml"));
        assertTrue(service.istZugeordnet("/mandatemenudetail.xhtml"));
        assertFalse(service.istZugeordnet("/demo.xhtml"));
    }

    @Test
    void menuLinksKanonischListetAlleLinks() {
        PageAccessGuardService service = strictMitMenues(menu("kontakte.html", true), menu("", true));
        assertTrue(service.menuLinksKanonisch().containsKey("kontakte"));
        assertEquals(1, service.menuLinksKanonisch().size(), "Container-Menues ohne Link tauchen nicht auf");
    }

    // ============================================================ Redirect

    @Test
    void redirectToAccessDeniedLeitetUm() throws IOException {
        PageAccessGuardService service = strictMitMenues();
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);
            when(externalContext.getRequestContextPath()).thenReturn("/app");

            service.redirectToAccessDenied();

            verify(externalContext).redirect("/app/access-denied.html");
            verify(facesContext).responseComplete();
        }
    }

    @Test
    void redirectToAccessDeniedOhneFacesContextTutNichts() throws IOException {
        PageAccessGuardService service = strictMitMenues();
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(null);
            // Sonar java:S2699 (Karte 891): "TutNichts" stand nur im Methodennamen. Ohne Assertion
            // war der Test auch dann gruen, wenn der Aufruf eine NullPointerException geworfen
            // haette — genau der Fall, den er abdecken soll. Jetzt steht die Zusage im Code.
            assertDoesNotThrow(service::redirectToAccessDenied,
                    "ohne FacesContext (z.B. im Cron-Lauf) darf der Aufruf folgenlos bleiben");
        }
    }
}
