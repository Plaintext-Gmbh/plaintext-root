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
import ch.plaintext.linktoken.LinkTokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Standard-{@link LinkTokenStore} (Karte 1559): dieselbe Tabelle {@code freigabe_link} wie die Freigabe-Links
 * (Karte 1476), also ein Token-Format, ein Zähler und eine Ablaufregel für alle Module. Das Token steht nur als
 * SHA-256 in der Tabelle, {@link LinkToken#token()} ist deshalb nur im Ergebnis von {@link #anlegen} gesetzt.
 *
 * <p>Angelegt, aufgelistet und widerrufen wird im Mandat des angemeldeten Benutzers; {@link #finde} kennt nur das
 * Token und liefert das Mandat des Links mit, damit das Modul das Objekt dort sucht. Der Zähler geht bei jedem
 * Aufruf direkt in die Zeile (wie bei den Freigabe-Links); wer weniger Schreiblast will, bringt einen eigenen Store.</p>
 */
@Slf4j
public class JpaLinkTokenStore implements LinkTokenStore {

    /** Base64url ohne Auffüllung, 24 Byte = 32 Zeichen; ältere Stores hatten bis 128. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final Pattern ART = Pattern.compile("[a-z0-9-]{1,40}");
    private static final SecureRandom ZUFALL = new SecureRandom();
    static final int MAX_OBJEKT = 1000;

    private final FreigabeLinkRepository links;
    private final ObjectProvider<PlaintextSecurity> security;

    public JpaLinkTokenStore(FreigabeLinkRepository links, ObjectProvider<PlaintextSecurity> security) {
        this.links = links;
        this.security = security;
    }

    @Override
    @Transactional
    public LinkToken anlegen(LinkTokenAntrag antrag) {
        if (antrag == null || antrag.art() == null || !ART.matcher(antrag.art()).matches()) {
            throw new IllegalArgumentException("art muss [a-z0-9-] sein, war " + (antrag == null ? null : antrag.art()));
        }
        String objekt = leerAlsNull(antrag.objekt());
        if (objekt == null || objekt.length() > MAX_OBJEKT) {
            throw new IllegalArgumentException("objekt muss 1.." + MAX_OBJEKT + " Zeichen lang sein");
        }
        String token = neuesToken();
        FreigabeLink l = new FreigabeLink();
        l.setMandat(mandat());
        l.setTyp(antrag.art());
        l.setSchluessel(objekt);
        l.setRecht((antrag.recht() == null ? LinkRecht.LESEN : antrag.recht()).wert());
        l.setBesitzer(leerAlsNull(antrag.besitzer()));
        l.setZweck(leerAlsNull(antrag.zweck()));
        l.setGueltigBis(antrag.gueltigBis());
        l.setTokenHash(hash(token));
        l = links.save(l);
        log.info("LinkToken {} angelegt: {}/{} recht={} gültig bis {}", l.getId(), l.getTyp(), objekt, l.getRecht(),
                l.getGueltigBis());
        return info(l, token);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LinkToken> finde(String art, String token) {
        if (art == null || token == null || !TOKEN.matcher(token.strip()).matches()) {
            return Optional.empty();
        }
        try {
            LocalDate heute = LocalDate.now();
            return links.findByTokenHashAndDeletedFalse(hash(token.strip()))
                    .filter(l -> l.getSchluessel() != null && art.equals(l.getTyp()))
                    .filter(l -> l.getGueltigBis() == null || !heute.isAfter(l.getGueltigBis()))
                    .map(l -> info(l, null));
        } catch (RuntimeException e) {
            log.warn("LinkToken {}: Tabelle nicht lesbar, Link abgelehnt | {}", art, e.toString());
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<LinkToken> liste(String art, String objekt, String besitzer) {
        String mandat = mandat();
        return links.findByTypAndSchluesselIsNotNullAndDeletedFalseOrderByIdDesc(art).stream()
                .filter(l -> mandat == null || mandat.equals(l.getMandat()))
                .filter(l -> objekt == null || objekt.equals(l.getSchluessel()))
                .filter(l -> besitzer == null || besitzer.equalsIgnoreCase(String.valueOf(l.getBesitzer())))
                .map(l -> info(l, null)).toList();
    }

    @Override
    @Transactional
    public boolean widerrufe(String art, String id) {
        Long nummer;
        try {
            nummer = Long.valueOf(id == null ? "" : id.strip());
        } catch (NumberFormatException e) {
            return false;
        }
        String mandat = mandat();
        Optional<FreigabeLink> link = links.findById(nummer)
                .filter(l -> !Boolean.TRUE.equals(l.getDeleted()) && l.getSchluessel() != null && l.getTyp().equals(art))
                .filter(l -> mandat == null || mandat.equals(l.getMandat()));
        link.ifPresent(l -> {
            l.setDeleted(true);
            links.save(l);
            log.info("LinkToken {} widerrufen", l.getId());
        });
        return link.isPresent();
    }

    @Override
    @Transactional
    public int widerrufeFuer(String art, String objekt) {
        if (art == null || objekt == null) {
            return 0;
        }
        List<FreigabeLink> alle = links.findByTypAndSchluesselAndDeletedFalse(art, objekt);
        alle.forEach(l -> l.setDeleted(true));
        links.saveAll(alle);
        return alle.size();
    }

    @Override
    @Transactional
    public int zieheUm(String art, String von, String nach) {
        if (art == null || von == null || nach == null || von.equals(nach)) {
            return 0;
        }
        List<FreigabeLink> alle = links.findByTypAndSchluesselAndDeletedFalse(art, von);
        alle.forEach(l -> l.setSchluessel(nach));
        links.saveAll(alle);
        return alle.size();
    }

    @Override
    @Transactional
    public void aufgerufen(String art, String token) {
        if (art == null || token == null || !TOKEN.matcher(token.strip()).matches()) {
            return;
        }
        try {
            links.findByTokenHashAndDeletedFalse(hash(token.strip()))
                    .filter(l -> l.getSchluessel() != null && art.equals(l.getTyp()))
                    .ifPresent(l -> {
                        l.setAufrufe(l.getAufrufe() + 1);
                        l.setZuletztAufgerufen(Instant.now());
                        links.save(l);
                    });
        } catch (RuntimeException e) {
            log.warn("LinkToken {}: Aufruf nicht gezählt | {}", art, e.toString());
        }
    }

    private String mandat() {
        PlaintextSecurity s = security.getIfAvailable();
        if (s == null) {
            return null;
        }
        try {
            return s.getMandat();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static LinkToken info(FreigabeLink l, String token) {
        return new LinkToken(String.valueOf(l.getId()), token, l.getTyp(), l.getSchluessel(), LinkRecht.von(l.getRecht()),
                l.getBesitzer(), l.getMandat(), l.getZweck() == null ? "" : l.getZweck(),
                l.getCreatedDate() == null ? null : l.getCreatedDate().toInstant(), l.getGueltigBis(), l.getAufrufe(),
                l.getZuletztAufgerufen());
    }

    static String neuesToken() {
        byte[] roh = new byte[24];
        ZUFALL.nextBytes(roh);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(roh);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String leerAlsNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
