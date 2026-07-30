/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.boot.search.SearchService.SearchResultGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchRestControllerTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("alice", "pw", List.of());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void unauthentifiziertLiefert401() {
        SecurityContextHolder.clearContext();
        SearchService svc = mock(SearchService.class);
        SearchRestController controller = new SearchRestController(svc);

        ResponseEntity<SearchRestController.SearchResponse> resp = controller.search("hallo");
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void mapptGruppenUndTrefferAufDtos() {
        authenticate();
        SearchService svc = mock(SearchService.class);
        when(svc.search("brief")).thenReturn(List.of(
                new SearchResultGroup("Korrespondenz",
                        List.of(new SearchHitDTO("Mahnung", "2026-01-01", "korrespondenz.html?id=1", "pi pi-envelope", 10)))));

        SearchRestController controller = new SearchRestController(svc);
        ResponseEntity<SearchRestController.SearchResponse> resp = controller.search("brief");

        assertEquals(200, resp.getStatusCode().value());
        SearchRestController.SearchResponse body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.groups().size());
        SearchRestController.GroupDto group = body.groups().get(0);
        assertEquals("Korrespondenz", group.module());
        assertEquals(1, group.hits().size());
        SearchRestController.HitDto hit = group.hits().get(0);
        assertEquals("Mahnung", hit.title());
        assertEquals("korrespondenz.html?id=1", hit.link());
        assertEquals("pi pi-envelope", hit.icon());
    }
}
