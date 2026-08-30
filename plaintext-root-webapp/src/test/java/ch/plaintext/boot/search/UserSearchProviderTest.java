/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.search.SearchProvider.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserSearchProviderTest {

    private static MyUserEntity user(long id, String username, String mandat) {
        MyUserEntity u = new MyUserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setMandat(mandat);
        return u;
    }

    @Test
    void ohneRolleKeineTreffer() {
        MyUserRepository repo = mock(MyUserRepository.class);
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(false);
        when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        assertTrue(p.search("alice", 10).isEmpty());
    }

    @Test
    void rootSiehtAlleMandanten() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                user(1, "alice", "m1"),
                user(2, "alicia", "m2")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        List<SearchHit> hits = p.search("alic", 10);
        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(h -> "useradmin.xhtml".equals(h.getLink())));
    }

    @Test
    void adminNurEigenerMandant() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                user(1, "alice", "m1"),
                user(2, "alicia", "m2")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(false);
        when(sec.ifGranted("ROLE_ADMIN")).thenReturn(true);
        when(sec.getMandat()).thenReturn("m1");

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        List<SearchHit> hits = p.search("alic", 10);
        assertEquals(1, hits.size());
        assertEquals("alice", hits.get(0).getTitle());
    }

    @Test
    void trefferPerId() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(user(42, "bob", "m1")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        List<SearchHit> hits = p.search("42", 10);
        assertEquals(1, hits.size());
        assertEquals("bob", hits.get(0).getTitle());
    }

    @Test
    void istNichtMenuScoped() {
        UserSearchProvider p = new UserSearchProvider(mock(MyUserRepository.class), mock(PlaintextSecurity.class));
        assertFalse(p.isMenuScoped());
        assertEquals("users", p.providerId());
        assertEquals("Benutzer", p.moduleTitle());
    }

    @Test
    void repositoryFehlerLiefertLeer() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenThrow(new RuntimeException("db weg"));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        assertTrue(p.search("alice", 10).isEmpty());
    }

    @Test
    void benutzerOhneUsernameWirdUebersprungen() {
        MyUserRepository repo = mock(MyUserRepository.class);
        MyUserEntity kaputt = new MyUserEntity();
        kaputt.setId(1L);
        kaputt.setUsername(null);
        when(repo.findAll()).thenReturn(List.of(kaputt, user(2, "alice", "m1")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        List<SearchHit> hits = p.search("alice", 10);
        assertEquals(1, hits.size());
        assertEquals("alice", hits.get(0).getTitle());
    }

    @Test
    void nichtPassenderBenutzerLiefertKeinenTreffer() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(user(1, "bob", "m1")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        assertTrue(p.search("zzz", 10).isEmpty());
    }

    @Test
    void exakterNameGibtHoechstenScore() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(user(1, "alice", "m1")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        List<SearchHit> hits = p.search("alice", 10);
        assertEquals(1, hits.size());
        assertEquals(100, hits.get(0).getScore());
    }

    @Test
    void teiltrefferImNamen() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(user(1, "malice", "m1")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        // "ali" sits in the middle of "malice" → contains hit (60).
        List<SearchHit> hits = p.search("ali", 10);
        assertEquals(1, hits.size());
        assertEquals(60, hits.get(0).getScore());
    }

    @Test
    void subtitleOhneMandantZeigtBenutzerId() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(user(7, "alice", null)));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(true);
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenReturn(false);

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        List<SearchHit> hits = p.search("alice", 10);
        assertEquals(1, hits.size());
        assertEquals("Benutzer #7", hits.get(0).getSubtitle());
    }

    @Test
    void rollenpruefungFehlerFuehrtZuKeinemTreffer() {
        MyUserRepository repo = mock(MyUserRepository.class);
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenThrow(new RuntimeException("security weg"));
        lenient().when(sec.ifGranted("ROLE_ADMIN")).thenThrow(new RuntimeException("security weg"));

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        assertTrue(p.search("alice", 10).isEmpty());
    }

    @Test
    void adminMitFehlerhaftemMandantLiefertNichts() {
        MyUserRepository repo = mock(MyUserRepository.class);
        when(repo.findAll()).thenReturn(List.of(user(1, "alice", "m1")));
        PlaintextSecurity sec = mock(PlaintextSecurity.class);
        when(sec.ifGranted("ROLE_ROOT")).thenReturn(false);
        when(sec.ifGranted("ROLE_ADMIN")).thenReturn(true);
        when(sec.getMandat()).thenThrow(new RuntimeException("kein mandant"));

        UserSearchProvider p = new UserSearchProvider(repo, sec);
        // safeMandat intercepts → null → an ADMIN without a tenant sees nothing.
        assertTrue(p.search("alice", 10).isEmpty());
    }
}
