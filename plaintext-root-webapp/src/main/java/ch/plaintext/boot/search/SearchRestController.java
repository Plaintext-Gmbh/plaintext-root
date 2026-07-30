/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.boot.search.SearchProvider.SearchHit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * REST-Endpunkt der globalen Suche (Cmd+K). Liefert die nach Modul gruppierten Treffer als JSON.
 * <p>
 * Läuft hinter der normalen Anwendungs-Authentifizierung – <b>keine Security-Config-Änderung
 * nötig</b> (alle {@code /api/**}-Endpunkte sind bereits authentifiziert). Das Frontend übernimmt
 * Debounce; dieser Endpunkt ist read-only über die bestehenden Registries/Daten.
 *
 * @author plaintext.ch
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@Tag(name = "Globale Suche", description = "Modulübergreifende Cmd+K-Suche; jedes Modul liefert seine Treffer inkl. Deep-Link.")
public class SearchRestController {

    private final SearchService searchService;

    public SearchRestController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(summary = "Globale Suche",
               description = "Sucht modulübergreifend und liefert die Treffer nach Modul gruppiert. "
                           + "Ein Provider wird nur abgefragt, wenn sein Modul für den Benutzer/Mandanten "
                           + "sichtbar ist. Queries unter 2 Zeichen liefern ein leeres Ergebnis.")
    @ApiResponse(responseCode = "200", description = "Trefferliste (ggf. leer)")
    @ApiResponse(responseCode = "401", description = "Benutzer ist nicht authentifiziert")
    @GetMapping
    public ResponseEntity<SearchResponse> search(
            @Parameter(description = "Suchbegriff") @RequestParam(name = "q", required = false) String q) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        List<GroupDto> groups = new ArrayList<>();
        for (SearchService.SearchResultGroup group : searchService.search(q)) {
            List<HitDto> hits = new ArrayList<>();
            for (SearchHit hit : group.hits()) {
                hits.add(new HitDto(hit.getTitle(), hit.getSubtitle(), hit.getLink(), hit.getIcon()));
            }
            groups.add(new GroupDto(group.module(), hits));
        }
        return ResponseEntity.ok(new SearchResponse(groups));
    }

    /** Antwort-Wurzel: {@code { "groups": [...] }}. */
    public record SearchResponse(List<GroupDto> groups) {
    }

    /** Eine Modul-Gruppe: {@code { "module": "...", "hits": [...] }}. */
    public record GroupDto(String module, List<HitDto> hits) {
    }

    /** Ein Treffer: {@code { "title", "subtitle", "link", "icon" }}. */
    public record HitDto(String title, String subtitle, String link, String icon) {
    }
}
