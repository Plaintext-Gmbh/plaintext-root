/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.framework.EigeneAdresse;
import ch.plaintext.freigabe.FreigabeInhalt;
import ch.plaintext.freigabe.FreigabeQuelle;
import ch.plaintext.freigabe.FreigabeRecht;
import ch.plaintext.freigabe.FreigabeZugriff;
import ch.plaintext.freigabe.link.entity.FreigabeLink;
import ch.plaintext.freigabe.link.repository.FreigabeLinkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Freigabe-Links ohne Anmeldung für die Objekte aller Module (Karte 1476), übernommen aus {@code DrawioLinkService}
 * und {@code PraesentationLinkService} (app): 24 Byte aus {@link SecureRandom}, in der DB nur der SHA-256, gültig bis
 * {@code gueltigBis}, widerrufbar, mit Aufrufzähler. Die Adresse gibt es nur einmal, beim Anlegen.
 *
 * <p>Anlegen, auflisten und widerrufen nur im eigenen Mandat und nur, wenn die {@link FreigabeQuelle} des Typs
 * {@link FreigabeQuelle#darfFreigeben} bejaht. Öffnen ({@link #zeige}, {@link #schreibe}) kennt nur das Token und
 * ruft das Modul mit leerem SecurityContext auf: eine mitgeschickte Sitzung zählt dort nicht.</p>
 */
@Slf4j
@Service
public class FreigabeLinkService {

    public static final int STANDARD_TAGE = 90;
    public static final int MAX_TAGE = 3650;
    public static final int MAX_TEIL = 200;
    public static final String PFAD = "/nosec/freigabe/";

    private static final String VORGABE_ADRESSE = "https://app.plaintext.ch";
    private static final SecureRandom ZUFALL = new SecureRandom();
    /** Base64url ohne Auffüllung: kein Punkt, also nie ein JWT, das der Bearer-Filter am /mcp annähme. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,100}");

    private final FreigabeLinkRepository links;
    private final ObjectProvider<FreigabeQuelle> quellen;
    private final PlaintextSecurity security;
    private final ObjectProvider<EigeneAdresse> adresse;

    public FreigabeLinkService(FreigabeLinkRepository links, ObjectProvider<FreigabeQuelle> quellen,
                               PlaintextSecurity security, ObjectProvider<EigeneAdresse> adresse) {
        this.links = links;
        this.quellen = quellen;
        this.security = security;
        this.adresse = adresse;
    }

    /** Ein Link für Oberfläche und MCP; {@code url} nur direkt nach dem Anlegen, sonst {@code null}. */
    public record Link(Long id, String typ, Long objektId, String teil, String recht, String zweck, String gueltigBis,
                       boolean abgelaufen, int aufrufe, String zuletztAufgerufen, String url) {
    }

    /** Ein {@code rw}-Aufruf über einen Link, der nur lesen darf. */
    public static class NurLesen extends RuntimeException {
        public NurLesen() {
            super("Der Link erlaubt nur Lesen.");
        }
    }

    /** @return die Typen, für die ein Modul eine {@link FreigabeQuelle} registriert hat */
    public List<FreigabeQuelle> quellen() {
        return quellen.orderedStream().toList();
    }

    /**
     * @param teil        {@code null} oder leer = ganzes Objekt
     * @param gueltigTage {@code null} = {@value #STANDARD_TAGE}, {@code 0} = unbefristet
     * @throws NoSuchElementException   wenn der Typ unbekannt ist oder der Benutzer das Objekt nicht freigeben darf
     * @throws IllegalArgumentException bei {@code rw} ohne Schreibfähigkeit des Moduls, zu langem Teil oder
     *                                  Gültigkeit ausserhalb 0..{@value #MAX_TAGE}
     */
    @Transactional
    public Link erzeuge(String typ, Long objektId, String teil, FreigabeRecht recht, Integer gueltigTage, String zweck) {
        FreigabeQuelle q = quelle(typ).filter(x -> objektId != null && x.darfFreigeben(objektId))
                .orElseThrow(() -> new NoSuchElementException("Objekt " + typ + "/" + objektId + " nicht gefunden"));
        FreigabeRecht r = recht == null ? FreigabeRecht.LESEN : recht;
        if (r == FreigabeRecht.SCHREIBEN && !q.schreibbar()) {
            throw new IllegalArgumentException("Typ " + typ + " kann über Links nicht schreiben, nur r ist möglich");
        }
        int tage = gueltigTage == null ? STANDARD_TAGE : gueltigTage;
        if (tage < 0 || tage > MAX_TAGE) {
            throw new IllegalArgumentException("gueltigTage muss zwischen 0 (unbefristet) und " + MAX_TAGE + " liegen, war " + tage);
        }
        String t = leerAlsNull(teil);
        if (t != null && t.length() > MAX_TEIL) {
            throw new IllegalArgumentException("teil darf höchstens " + MAX_TEIL + " Zeichen lang sein");
        }
        byte[] roh = new byte[24];
        ZUFALL.nextBytes(roh);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(roh);
        FreigabeLink l = new FreigabeLink();
        l.setMandat(security.getMandat());
        l.setTyp(q.typ());
        l.setObjektId(objektId);
        l.setTeil(t);
        l.setRecht(r.kurz());
        l.setTokenHash(hash(token));
        l.setZweck(leerAlsNull(zweck));
        l.setGueltigBis(tage == 0 ? null : LocalDate.now().plusDays(tage));
        l = links.save(l);
        log.info("Freigabe-Link {} angelegt: {}/{} teil={} recht={} gültig bis {}", l.getId(), l.getTyp(), objektId, t,
                l.getRecht(), l.getGueltigBis());
        String basis = Optional.ofNullable(adresse.getIfAvailable()).map(a -> a.basis(VORGABE_ADRESSE)).orElse(VORGABE_ADRESSE);
        Link i = info(l);
        return new Link(i.id(), i.typ(), i.objektId(), i.teil(), i.recht(), i.zweck(), i.gueltigBis(), false, 0, null,
                basis + PFAD + token);
    }

    /**
     * @param typ      {@code null} = alle Typen
     * @param objektId {@code null} = alle Objekte
     * @return die Links des Mandats, deren Objekt der Benutzer freigeben darf
     */
    public List<Link> liste(String typ, Long objektId) {
        return links.findByMandatAndDeletedFalseOrderByIdDesc(security.getMandat()).stream()
                .filter(l -> typ == null || typ.equals(l.getTyp()))
                .filter(l -> objektId == null || objektId.equals(l.getObjektId()))
                .filter(this::darf)
                .map(FreigabeLinkService::info).toList();
    }

    /** @throws NoSuchElementException wenn der Link nicht im Mandat liegt oder der Benutzer sein Objekt nicht freigeben darf */
    @Transactional
    public void widerrufe(Long linkId) {
        FreigabeLink l = links.findByIdAndMandatAndDeletedFalse(linkId, security.getMandat()).filter(this::darf)
                .orElseThrow(() -> new NoSuchElementException("Link " + linkId + " nicht gefunden"));
        l.setDeleted(true);
        links.save(l);
        log.info("Freigabe-Link {} widerrufen", linkId);
    }

    /**
     * GET hinter einem Token: das Modul stellt dar, nichts ändert sich ausser dem Zähler am Link.
     *
     * @return leer bei unbekanntem, abgelaufenem oder widerrufenem Token, unbekanntem Typ und wenn das Modul nichts
     *         liefert; die Fälle sind von aussen nicht zu unterscheiden
     */
    @Transactional
    public Optional<FreigabeInhalt> zeige(String token) {
        return oeffne(token).flatMap(o -> aufruf(o, () -> o.quelle().zeige(o.zugriff())));
    }

    /**
     * POST hinter einem Token: nur für {@code rw}-Links.
     *
     * @return leer wie bei {@link #zeige}
     * @throws NurLesen wenn der Link gültig ist, aber nur lesen darf
     */
    @Transactional
    public Optional<FreigabeInhalt> schreibe(String token, String contentType, byte[] inhalt) {
        Optional<Offen> offen = oeffne(token);
        if (offen.isPresent() && offen.get().zugriff().recht() != FreigabeRecht.SCHREIBEN) {
            log.info("Freigabe-Link {}: Schreibversuch mit r-Link abgewiesen", offen.get().link().getId());
            throw new NurLesen();
        }
        return offen.flatMap(o -> aufruf(o, () -> o.quelle().schreibe(o.zugriff(), contentType, inhalt)));
    }

    private record Offen(FreigabeLink link, FreigabeQuelle quelle, FreigabeZugriff zugriff) {
    }

    private Optional<Offen> oeffne(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return links.findByTokenHashAndDeletedFalse(hash(token))
                .filter(l -> l.getGueltigBis() == null || !LocalDate.now().isAfter(l.getGueltigBis()))
                .flatMap(l -> quelle(l.getTyp()).map(q -> new Offen(l, q, new FreigabeZugriff(l.getId(), l.getMandat(),
                        l.getTyp(), l.getObjektId(), l.getTeil(), FreigabeRecht.von(l.getRecht())))));
    }

    /** Ruft das Modul ohne Anmeldung auf und zählt den Aufruf, wenn es etwas liefert. */
    private Optional<FreigabeInhalt> aufruf(Offen o, Supplier<Optional<FreigabeInhalt>> modul) {
        Optional<FreigabeInhalt> antwort;
        SecurityContext vorher = SecurityContextHolder.getContext();
        SecurityContextHolder.clearContext();
        try {
            antwort = modul.get();
        } catch (RuntimeException e) {
            log.warn("Freigabe-Link {} auf {}/{}: Modul meldet {}", o.link().getId(), o.link().getTyp(),
                    o.link().getObjektId(), e.toString());
            antwort = Optional.empty();
        } finally {
            SecurityContextHolder.setContext(vorher);
        }
        if (antwort.isPresent()) {
            FreigabeLink l = o.link();
            l.setAufrufe(l.getAufrufe() + 1);
            l.setZuletztAufgerufen(Instant.now());
            links.save(l);
        }
        return antwort;
    }

    private Optional<FreigabeQuelle> quelle(String typ) {
        return quellen.orderedStream().filter(q -> q.typ().equals(typ)).findFirst();
    }

    private boolean darf(FreigabeLink l) {
        try {
            return quelle(l.getTyp()).map(q -> q.darfFreigeben(l.getObjektId())).orElse(false);
        } catch (RuntimeException e) {
            log.debug("Freigabe-Link {}: darfFreigeben wirft {}", l.getId(), e.toString());
            return false;
        }
    }

    static Link info(FreigabeLink l) {
        boolean abgelaufen = l.getGueltigBis() != null && LocalDate.now().isAfter(l.getGueltigBis());
        return new Link(l.getId(), l.getTyp(), l.getObjektId(), l.getTeil(), l.getRecht(), l.getZweck(),
                l.getGueltigBis() == null ? "unbefristet" : l.getGueltigBis().toString(), abgelaufen, l.getAufrufe(),
                l.getZuletztAufgerufen() == null ? null : l.getZuletztAufgerufen().toString(), null);
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
