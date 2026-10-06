/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Draws and runs the pages of the mobile framework (Karte 1355).
 *
 * <h2>Three addresses</h2>
 *
 * <ul>
 *   <li>{@code GET /watch/m/{id}} — the whole page: frame, status line, content. One HTML
 *       document; the stylesheet and the script come from the cache after the first visit.</li>
 *   <li>{@code POST /watch/m/{id}/{aktion}} — one action. Asked for JSON (that is what
 *       {@code mobil.js} does), it answers with the new content and a sentence for the status
 *       line, and the page is not reloaded. Asked for anything else (no JavaScript), it
 *       answers 303 back to the page — the classic post/redirect/get.</li>
 *   <li>{@code GET /watch/m/_/{datei}} — {@code watch.css} and {@code mobil.js}, cached for a
 *       year under an address that changes with their content ({@link MobilDateien}).</li>
 * </ul>
 *
 * <h2>Security, and why nothing here is new</h2>
 *
 * <ul>
 *   <li><b>Sign-in and phone link</b>: everything lies under {@code /watch/}. A signed-out
 *       caller falls under {@code anyRequest().authenticated()} and is sent to the login. A
 *       phone-link session is let through by {@code WatchTokenSitzungFilter} exactly as on the
 *       Facelet pages — and the token is re-checked against the database on every request,
 *       including every action.</li>
 *   <li><b>Which page</b>: {@link WatchPage#available()} — the access rule (roles), checked for
 *       showing and for acting, fail-closed. A page that says no answers 404, the same as one that
 *       does not exist, so the answer does not tell which of the two it is. The user's own switch
 *       ({@code abgeschaltete_seiten}) is NOT an access rule: it only takes a page out of the
 *       rotation. A switched-off page opened directly still shows (card 1355 follow-up, 01.10.2026:
 *       Daniel opened {@code /watch/m/alkohol} with "alkohol" switched off and got 404).</li>
 *   <li><b>CSRF</b>: every action is a {@code POST}; Spring Security checks the token (form
 *       field or {@code X-CSRF-TOKEN} header) before this controller is reached. The token is
 *       written into each form of the page.</li>
 *   <li><b>CSP</b>: this is not a Faces path, so the header of {@code PlaintextSecurityConfig}
 *       applies unchanged. The page has no inline script and no {@code style} attribute
 *       ({@link MobilHtml}).</li>
 * </ul>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class MobilSeitenController {

    /** Action names: what a module may call an action. Anything else is refused before the module is asked. */
    static final Pattern AKTION = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    /** Session key of the status line after a post without JavaScript (post/redirect/get). */
    static final String MELDUNG_SCHLUESSEL = "watch.mobil.meldung";

    static final String NICHT_GEFUNDEN = "Diese Seite gibt es hier nicht.";
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8);

    /**
     * The policy of these pages — stricter than the one of the house, on purpose.
     *
     * <p>{@code PlaintextSecurityConfig.cspPolicy} still carries {@code 'unsafe-inline'} in
     * {@code script-src} and {@code style-src} wherever an application has not thrown the switch
     * (app, measured 01.10.2026), because Facelet pages and PrimeFaces need it. These pages need
     * neither: no inline script, no {@code style} attribute, no foreign host ({@link MobilHtml},
     * held by {@code MobilHtmlTest}). So they get the policy they can keep. Spring Security's
     * header writer only sets its own header when the response has none yet — the one set here
     * wins, and {@code MobilSeitePlaywrightIT} reads the header the browser actually got.</p>
     */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'self'";

    static final String FEHLGESCHLAGEN = "Hat nicht geklappt.";

    /** At most this many fields per action reach the page (card 1387). */
    public static final int FELDER_MAX = 10;

    /** A field longer than this is cut — a time is five characters, a note fits in two hundred. */
    public static final int FELD_LAENGE_MAX = 200;

    private final WatchPageRegistry registry;
    private final WatchStateService zustand;
    private final MobilDateien dateien;

    // ═══════════════════════════════════════════════════════════════════ Seite

    @GetMapping(MobilWatchPage.PFAD + "{id}")
    public ResponseEntity<String> seite(@PathVariable("id") String id,
                                        @RequestParam(name = "frage", required = false) String frage,
                                        HttpServletRequest request) {
        Optional<MobilWatchPage> gefunden = finde(id);
        if (gefunden.isEmpty()) {
            return nichtGefunden();
        }
        MobilWatchPage seite = gefunden.get();
        // Wie WatchFrameBean.seitenaufruf(): die gezeigte Seite ist die gemerkte. Darum darf
        // "weiter" ein einfacher Link sein — die naechste Seite merkt sich beim Anzeigen selbst.
        zustand.merkeSeite(seite.id());

        String meldung = null;
        HttpSession sitzung = request.getSession(false);
        if (sitzung != null && sitzung.getAttribute(MELDUNG_SCHLUESSEL) instanceof String gemerkt) {
            sitzung.removeAttribute(MELDUNG_SCHLUESSEL);
            meldung = gemerkt;
        }
        String kontext = request.getContextPath();
        String inhalt = inhalt(seite, request, frage);
        // Einmal je Anfrage (Karte 1387): Position und Weiter brauchen denselben Umlauf, und jede
        // Berechnung fragt die Zugriffsregel jeder Seite beim Seitenwaechter.
        List<WatchPage> umlauf = registry.verfuegbare();
        MobilHtml.Rahmen rahmen = new MobilHtml.Rahmen(
                seite.title(),
                position(seite, umlauf),
                registry.naechste(seite.id(), umlauf).map(p -> kontext + adresse(p)).orElse(null),
                registry.uebersicht().map(p -> kontext + adresse(p)).orElse(null),
                kontext + dateien.adresse("watch.css"),
                kontext + dateien.adresse("mobil.js"),
                meldung,
                false);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Security-Policy", CSP)
                .body(MobilHtml.seite(rahmen, inhalt));
    }

    // ═══════════════════════════════════════════════════════════════════ Aktion

    @PostMapping(MobilWatchPage.PFAD + "{id}/{aktion}")
    public ResponseEntity<Object> aktion(@PathVariable("id") String id,
                                    @PathVariable("aktion") String aktion,
                                    @RequestParam(name = "wert", required = false) String wert,
                                    HttpServletRequest request) {
        if (!AKTION.matcher(aktion).matches()) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Unbekannte Aktion.");
        }
        Optional<MobilWatchPage> gefunden = finde(id);
        if (gefunden.isEmpty()) {
            // Gleiche Antwort wie nichtGefunden(), aber als Object (Karte 1416, Sonar S1452: kein Wildcard-Rueckgabetyp).
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(TEXT).body(NICHT_GEFUNDEN);
        }
        MobilWatchPage seite = gefunden.get();

        MobilAntwort antwort;
        try {
            antwort = seite.handle(aktion, wert, felder(request));
            if (antwort == null) {
                antwort = MobilAntwort.ok(null);
            }
        } catch (RuntimeException e) {
            log.warn("Mobil: Aktion {} auf {} fehlgeschlagen: {}", aktion, seite.id(), e.toString());
            antwort = MobilAntwort.fehler(FEHLGESCHLAGEN);
        }

        if (!willJson(request)) {
            // Nur der Satz, nicht der Datensatz: was in der Sitzung liegt, muss serialisierbar sein.
            if (antwort.meldung() != null) {
                request.getSession(true).setAttribute(MELDUNG_SCHLUESSEL, antwort.meldung());
            }
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(request.getContextPath() + seite.view()))
                    .build();
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", antwort.ok());
        json.put("meldung", antwort.meldung() == null ? "" : antwort.meldung());
        json.put("inhalt", inhalt(seite, request, null));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    // ═══════════════════════════════════════════════════════════════════ Dateien

    @GetMapping(MobilWatchPage.PFAD + "_/{datei}")
    public ResponseEntity<byte[]> datei(@PathVariable("datei") String name,
                                        @RequestParam(name = "v", required = false) String marke) {
        Optional<MobilDateien.Datei> d = dateien.datei(name);
        if (d.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MobilDateien.Datei datei = d.get();
        // Ein Jahr nur unter der richtigen Marke. Eine veraltete Marke (eine Seite aus dem
        // Cache, die nach einem Release noch die alte Adresse nennt) bekommt den neuen Inhalt,
        // darf ihn aber nicht festhalten — sonst klebte der alte Link auf dem neuen Stand.
        CacheControl cache = datei.marke().equals(marke)
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.noCache();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, datei.typ())
                .cacheControl(cache)
                .body(datei.inhalt());
    }

    // ═══════════════════════════════════════════════════════════════════ Hilfen

    /** The page, if it exists, is drawn by this framework and is visible to the caller. */
    private Optional<MobilWatchPage> finde(String id) {
        return registry.byId(id)
                .filter(MobilWatchPage.class::isInstance)
                .map(MobilWatchPage.class::cast)
                .filter(MobilSeitenController::erlaubt);
    }

    /** Access rule only (roles), fail-closed — the user's own switch does not apply here. */
    private static boolean erlaubt(WatchPage seite) {
        try {
            return seite.available();
        } catch (RuntimeException e) {
            log.warn("Mobil: Zugriffsregel von {} nicht auswertbar: {}", seite.id(), e.toString());
            return false;
        }
    }

    private String inhalt(MobilWatchPage seite, HttpServletRequest request, String frage) {
        String kontext = request.getContextPath();
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        MobilHtml.Formular formular = new MobilHtml.Formular(
                kontext + seite.view(),
                kontext + seite.view() + "/",
                token == null ? null : token.getParameterName(),
                token == null ? null : token.getToken(),
                frage);
        MobilSeite beschreibung;
        try {
            beschreibung = seite.beschreibe();
        } catch (RuntimeException e) {
            // Eine Seite, die ihre Daten nicht lesen kann, zeigt das in Worten statt einer
            // Fehlerseite — dieselbe Haltung wie WatchHomePage bei einer kaputten Kachel.
            log.warn("Mobil: Seite {} nicht beschreibbar: {}", seite.id(), e.toString());
            beschreibung = MobilSeite.neu().hinweis("Die Daten sind gerade nicht lesbar.").bauen();
        }
        return MobilHtml.inhalt(beschreibung, formular);
    }

    /**
     * The fields of the form: only parameters named {@code f-<name>} with a name a
     * {@link MobilSeite.Feld} may have, at most {@link #FELDER_MAX}, each cut to
     * {@link #FELD_LAENGE_MAX}. Never the CSRF token, never {@code wert}.
     */
    static Map<String, String> felder(HttpServletRequest request) {
        Map<String, String> felder = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> p : request.getParameterMap().entrySet()) {
            if (felder.size() < FELDER_MAX) {
                feld(felder, p.getKey(), p.getValue());
            }
        }
        return Map.copyOf(felder);
    }

    /** Ein Parameter: nur mit Praefix, gueltigem Kurznamen und Wert; gekuerzt (Karte 1416, Sonar java:S135). */
    private static void feld(Map<String, String> felder, String name, String[] werte) {
        if (!name.startsWith(MobilHtml.FELD_PRAEFIX)) {
            return;
        }
        String kurz = name.substring(MobilHtml.FELD_PRAEFIX.length());
        if (!MobilSeite.Feld.NAME_MUSTER.matcher(kurz).matches() || werte == null || werte.length == 0) {
            return;
        }
        String w = werte[0] == null ? "" : werte[0];
        felder.put(kurz, w.length() > FELD_LAENGE_MAX ? w.substring(0, FELD_LAENGE_MAX) : w);
    }

    private static String position(WatchPage seite, List<WatchPage> umlauf) {
        for (int i = 0; i < umlauf.size(); i++) {
            if (umlauf.get(i).id().equals(seite.id())) {
                return (i + 1) + "/" + umlauf.size();
            }
        }
        return "";
    }

    private static String adresse(WatchPage p) {
        return p.view().replace(".xhtml", ".html");
    }

    private static boolean willJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private static ResponseEntity<String> nichtGefunden() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(TEXT).body(NICHT_GEFUNDEN);
    }
}
