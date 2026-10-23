/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files;

import ch.plaintext.linktoken.LinkRecht;
import ch.plaintext.linktoken.LinkToken;
import ch.plaintext.linktoken.LinkTokenAntrag;
import ch.plaintext.linktoken.LinkTokenStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Testhilfe: LinkTokenStore im Speicher, behält das Token wie der Inventar-Store (Klartext). */
public class SpeicherLinkTokenStore implements LinkTokenStore {

    public final List<LinkToken> zeilen = new ArrayList<>();

    @Override
    public LinkToken anlegen(LinkTokenAntrag a) {
        LinkToken l = new LinkToken(String.valueOf(zeilen.size() + 1), UUID.randomUUID().toString().replace("-", ""),
                a.art(), a.objekt(), a.recht() == null ? LinkRecht.LESEN : a.recht(), a.besitzer(), null,
                a.zweck() == null ? "" : a.zweck().strip(), Instant.now(), a.gueltigBis(), 0, null);
        zeilen.add(l);
        return l;
    }

    @Override
    public Optional<LinkToken> finde(String art, String token) {
        return zeilen.stream().filter(l -> l.art().equals(art) && l.token().equals(token)
                && !l.abgelaufen(LocalDate.now())).findFirst();
    }

    @Override
    public List<LinkToken> liste(String art, String objekt, String besitzer) {
        return zeilen.stream().filter(l -> l.art().equals(art) && (objekt == null || objekt.equals(l.objekt()))
                && (besitzer == null || besitzer.equals(l.besitzer()))).toList();
    }

    @Override
    public boolean widerrufe(String art, String id) {
        return zeilen.removeIf(l -> l.art().equals(art) && l.id().equals(id));
    }

    @Override
    public int widerrufeFuer(String art, String objekt) {
        int vorher = zeilen.size();
        zeilen.removeIf(l -> l.art().equals(art) && l.objekt().equals(objekt));
        return vorher - zeilen.size();
    }

    @Override
    public int zieheUm(String art, String von, String nach) {
        return 0;
    }

    @Override
    public void aufgerufen(String art, String token) {
        zeilen.replaceAll(l -> l.art().equals(art) && l.token().equals(token)
                ? new LinkToken(l.id(), l.token(), l.art(), l.objekt(), l.recht(), l.besitzer(), l.mandat(), l.zweck(),
                l.erstellt(), l.gueltigBis(), l.aufrufe() + 1, Instant.now()) : l);
    }

    /** Für Tests: setzt das Ablaufdatum eines Links. */
    public void setzeBis(int index, LocalDate bis) {
        LinkToken l = zeilen.get(index);
        zeilen.set(index, new LinkToken(l.id(), l.token(), l.art(), l.objekt(), l.recht(), l.besitzer(), l.mandat(),
                l.zweck(), l.erstellt(), bis, l.aufrufe(), l.zuletztAufgerufen()));
    }
}
