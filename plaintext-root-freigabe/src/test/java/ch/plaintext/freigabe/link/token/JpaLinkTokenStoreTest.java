/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.token;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.freigabe.link.entity.FreigabeLink;
import ch.plaintext.freigabe.link.repository.FreigabeLinkRepository;
import ch.plaintext.linktoken.LinkRecht;
import ch.plaintext.linktoken.LinkToken;
import ch.plaintext.linktoken.LinkTokenAntrag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Karte 1559: der Standard-LinkTokenStore. Die Fälle sind aus dem {@code DrawioLinkServiceTest} des Inventars
 * übernommen (ein Token gilt für genau ein Objekt, bis zum Ablaufdatum, solange es nicht widerrufen ist), dazu das
 * Nachziehen beim Verschieben und der gemeinsame Zähler. Repository als Liste im Speicher.
 */
class JpaLinkTokenStoreTest {

    private final List<FreigabeLink> zeilen = new ArrayList<>();
    private String mandat = "m1";
    private JpaLinkTokenStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        FreigabeLinkRepository repo = mock(FreigabeLinkRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            FreigabeLink l = i.getArgument(0);
            if (l.getId() == null) {
                l.setId((long) zeilen.size() + 1);
                if (l.getMandat() == null) {
                    l.setMandat(mandat);
                }
                zeilen.add(l);
            }
            return l;
        });
        when(repo.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.findByTokenHashAndDeletedFalse(anyString())).thenAnswer(i -> zeilen.stream()
                .filter(l -> !Boolean.TRUE.equals(l.getDeleted()) && l.getTokenHash().equals(i.getArgument(0))).findFirst());
        when(repo.findById(anyLong())).thenAnswer(i -> zeilen.stream().filter(l -> l.getId().equals(i.getArgument(0))).findFirst());
        when(repo.findByTypAndSchluesselIsNotNullAndDeletedFalseOrderByIdDesc(anyString())).thenAnswer(i -> zeilen.stream()
                .filter(l -> !Boolean.TRUE.equals(l.getDeleted()) && l.getSchluessel() != null && l.getTyp().equals(i.getArgument(0)))
                .sorted(Comparator.comparing(FreigabeLink::getId).reversed()).toList());
        when(repo.findByTypAndSchluesselAndDeletedFalse(anyString(), anyString())).thenAnswer(i -> zeilen.stream()
                .filter(l -> !Boolean.TRUE.equals(l.getDeleted()) && l.getTyp().equals(i.getArgument(0))
                        && i.getArgument(1).equals(l.getSchluessel())).toList());
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.getMandat()).thenAnswer(i -> mandat);
        ObjectProvider<PlaintextSecurity> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(security);
        store = new JpaLinkTokenStore(repo, provider);
    }

    private LinkToken neu(String objekt, LinkRecht recht, LocalDate bis) {
        return store.anlegen(new LinkTokenAntrag("drawio", objekt, recht, "anna", " Review ", bis));
    }

    @Test
    void erzeugterLinkOeffnetGenauDiesesObjektUndNurSeineArt() {
        LinkToken l = neu("pfs.drawio", null, LocalDate.now().plusDays(90));

        assertThat(l.token()).hasSize(32).matches("[A-Za-z0-9_-]+");
        assertThat(l.recht()).isEqualTo(LinkRecht.LESEN);
        assertThat(l.zweck()).isEqualTo("Review");
        assertThat(store.finde("drawio", l.token())).get().satisfies(f -> {
            assertThat(f.objekt()).isEqualTo("pfs.drawio");
            assertThat(f.token()).as("nur der Hash liegt in der Tabelle").isNull();
            assertThat(f.mandat()).isEqualTo("m1");
        });
        assertThat(store.finde("files", l.token())).as("andere Art").isEmpty();
        assertThat(store.finde("drawio", "falsch")).isEmpty();
        assertThat(store.finde("drawio", null)).isEmpty();
        assertThat(zeilen.get(0).getTokenHash()).isNotEqualTo(l.token()).hasSize(64);
    }

    @Test
    void schreibRechtWirdGespeichertAlteWerteSindLesen() {
        LinkToken s = neu("pfs.drawio", LinkRecht.SCHREIBEN, null);
        assertThat(store.finde("drawio", s.token())).get().satisfies(f -> assertThat(f.schreiben()).isTrue());
        assertThat(LinkRecht.von(null)).isEqualTo(LinkRecht.LESEN);
        assertThat(LinkRecht.von("")).isEqualTo(LinkRecht.LESEN);
        assertThat(LinkRecht.von("rw")).isEqualTo(LinkRecht.SCHREIBEN);
    }

    @Test
    void abgelaufenesTokenOeffnetNichtUndStehtAbgelaufenInDerListe() {
        LinkToken l = neu("pfs.drawio", null, LocalDate.now().minusDays(1));
        LinkToken heute = neu("teams.drawio", null, LocalDate.now());

        assertThat(store.finde("drawio", l.token())).isEmpty();
        assertThat(store.finde("drawio", heute.token())).as("der letzte Tag gilt noch").isPresent();
        assertThat(store.liste("drawio", "pfs.drawio", null)).singleElement()
                .satisfies(x -> assertThat(x.abgelaufen(LocalDate.now())).isTrue());
    }

    @Test
    void ohneAblaufdatumUnbefristet() {
        LinkToken l = neu("pfs.drawio", null, null);
        assertThat(l.gueltigBis()).isNull();
        assertThat(store.finde("drawio", l.token())).isPresent();
    }

    @Test
    void widerrufenSchliesstDenLink() {
        LinkToken l = neu("pfs.drawio", null, null);
        assertThat(store.widerrufe("drawio", l.id())).isTrue();
        assertThat(store.finde("drawio", l.token())).isEmpty();
        assertThat(store.widerrufe("drawio", l.id())).isFalse();
        assertThat(store.widerrufe("drawio", "keine-zahl")).isFalse();
    }

    @Test
    void fremdesMandatSiehtUndWiderruftNichts() {
        LinkToken l = neu("pfs.drawio", null, null);
        mandat = "m2";
        assertThat(store.liste("drawio", null, null)).isEmpty();
        assertThat(store.widerrufe("drawio", l.id())).isFalse();
        assertThat(store.finde("drawio", l.token())).as("öffnen kennt nur das Token").isPresent();
    }

    @Test
    void loeschenDesObjektsNimmtSeineLinksMit() {
        LinkToken pfs = neu("pfs.drawio", null, null);
        LinkToken teams = neu("teams.drawio", null, null);

        assertThat(store.widerrufeFuer("drawio", "pfs.drawio")).isEqualTo(1);
        assertThat(store.finde("drawio", pfs.token())).isEmpty();
        assertThat(store.finde("drawio", teams.token())).isPresent();
        assertThat(store.widerrufeFuer("drawio", "pfs.drawio")).isZero();
    }

    @Test
    void verschobenesObjektBehaeltSeinenLink() {
        LinkToken l = neu("pfs.drawio", null, null);
        assertThat(store.zieheUm("drawio", "pfs.drawio", "architektur/pfs.drawio")).isEqualTo(1);
        assertThat(store.finde("drawio", l.token())).get()
                .satisfies(f -> assertThat(f.objekt()).isEqualTo("architektur/pfs.drawio"));
    }

    @Test
    void aufrufeWerdenGezaehlt() {
        LinkToken l = neu("pfs.drawio", null, null);
        store.aufgerufen("drawio", l.token());
        store.aufgerufen("drawio", l.token());
        store.aufgerufen("files", l.token());
        store.aufgerufen("drawio", "../kaputt");

        LinkToken x = store.liste("drawio", null, "ANNA").getFirst();
        assertThat(x.aufrufe()).isEqualTo(2);
        assertThat(x.zuletztAufgerufen()).isNotNull();
        assertThat(store.liste("drawio", null, "bert")).isEmpty();
    }

    @Test
    void freigabeLinkZeilenGehoerenNichtZumStore() {
        FreigabeLink alt = new FreigabeLink();
        alt.setId(99L);
        alt.setTyp("drawio");
        alt.setObjektId(7L);
        alt.setTokenHash(JpaLinkTokenStore.hash("abcdefghijklmnopqrstuvwxyz012345"));
        alt.setRecht("r");
        alt.setMandat("m1");
        zeilen.add(alt);

        assertThat(store.finde("drawio", "abcdefghijklmnopqrstuvwxyz012345")).isEmpty();
        assertThat(store.liste("drawio", null, null)).isEmpty();
        assertThat(store.widerrufe("drawio", "99")).isFalse();
    }

    @Test
    void ungueltigerAntragWirdAbgelehnt() {
        assertThatThrownBy(() -> store.anlegen(new LinkTokenAntrag("Draw IO", "x", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.anlegen(new LinkTokenAntrag("drawio", " ", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(zeilen).isEmpty();
    }

    @Test
    void tokensSindVerschieden() {
        assertThat(JpaLinkTokenStore.neuesToken()).isNotEqualTo(JpaLinkTokenStore.neuesToken());
        assertThat(Optional.ofNullable(store.liste("drawio", null, null))).get().asList().isEmpty();
    }
}
