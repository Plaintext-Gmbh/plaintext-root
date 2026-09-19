/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.framework.EigeneAdresse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 1257: issuing, revoking and re-issuing the phone link.
 *
 * <p>The one property that everything else hangs on: <b>revoke first, issue second</b>. The
 * other way round a failure between the two steps leaves two valid links behind while the user
 * is told the old one is dead — and a link handed out once can then never be taken back.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchHandyLinkServiceTest {

    private IApiTokenService tokenDienst;
    private WatchStateService zustand;
    private PlaintextSecurity sicherheit;
    private WatchHandyLinkService dienst;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> liefert(T wert) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(wert);
        return p;
    }

    @BeforeEach
    void setUp() {
        tokenDienst = mock(IApiTokenService.class);
        zustand = mock(WatchStateService.class);
        sicherheit = mock(PlaintextSecurity.class);
        when(sicherheit.getId()).thenReturn(7L);
        when(sicherheit.getMandat()).thenReturn("plaintext");
        when(sicherheit.getEmailForUser(7L)).thenReturn(Optional.of("daniel@plaintext.ch"));

        EigeneAdresse adresse = mock(EigeneAdresse.class);
        when(adresse.basis("")).thenReturn("https://app.plaintext.ch");

        dienst = new WatchHandyLinkService(zustand, sicherheit, liefert(tokenDienst), liefert(adresse));
    }

    /** A JWT whose payload carries a jti — header and signature are irrelevant here. */
    private static String jwtMitJti(String jti) {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"7\",\"jti\":\"" + jti + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "kopf." + payload + ".signatur";
    }

    @Test
    @DisplayName("Der Link traegt die Basis aus EigeneAdresse und den Einstiegspfad")
    void linkAufbau() {
        when(tokenDienst.createToken(eq(7L), eq("plaintext"), eq(WatchHandyLinkService.TOKEN_NAME),
                anyString(), anyInt(), anyString())).thenReturn(jwtMitJti("jti-1"));

        String link = dienst.erzeuge();

        assertEquals("https://app.plaintext.ch/nosec/watch?t=" + jwtMitJti("jti-1"), link);
    }

    @Test
    @DisplayName("Die Adresse ist NICHT fest verdrahtet — eine andere Installation liefert eine andere")
    void adresseKommtAusDerEinstellung() {
        // Karte 1046: waere sie verdrahtet, zeigte der Link jedes anderen Mandanten auf eine
        // fremde Anwendung.
        EigeneAdresse andere = mock(EigeneAdresse.class);
        when(andere.basis("")).thenReturn("https://guild.plaintext.ch");
        dienst = new WatchHandyLinkService(zustand, sicherheit, liefert(tokenDienst), liefert(andere));
        when(tokenDienst.createToken(eq(7L), eq("plaintext"), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(jwtMitJti("jti-1"));

        assertTrue(dienst.erzeuge().startsWith("https://guild.plaintext.ch/"));
    }

    @Test
    @DisplayName("Neu generieren widerruft ZUERST und stellt erst danach aus")
    void widerrufVorAusstellung() {
        when(tokenDienst.createToken(eq(7L), eq("plaintext"), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(jwtMitJti("jti-2"));

        dienst.erzeuge();

        InOrder reihenfolge = inOrder(tokenDienst);
        reihenfolge.verify(tokenDienst).invalidateTokensByName(7L, "plaintext",
                WatchHandyLinkService.TOKEN_NAME);
        reihenfolge.verify(tokenDienst).createToken(eq(7L), eq("plaintext"),
                eq(WatchHandyLinkService.TOKEN_NAME), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("Der Token traegt das kleinste Recht und den Browser-Namenspraefix")
    void kleinstesRecht() {
        when(tokenDienst.createToken(eq(7L), eq("plaintext"), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(jwtMitJti("jti-3"));

        dienst.erzeuge();

        verify(tokenDienst).createToken(7L, "plaintext", WatchHandyLinkService.TOKEN_NAME,
                "daniel@plaintext.ch", WatchHandyLinkService.GUELTIG_TAGE, "READ");
        assertTrue(WatchHandyLinkService.TOKEN_NAME.startsWith(IApiTokenService.UI_TOKEN_NAME_PREFIX),
                "ohne den Praefix waere der Link am API ein lesender Vollzugang");
        assertEquals("READ", WatchHandyLinkService.SCOPE);
    }

    @Test
    @DisplayName("Die jti wird gemerkt, der Token selbst nicht")
    void jtiGemerkt() {
        when(tokenDienst.createToken(eq(7L), eq("plaintext"), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(jwtMitJti("jti-4711"));

        dienst.erzeuge();

        verify(zustand).merkeHandyLink("jti-4711");
    }

    @Test
    @DisplayName("Ein unlesbarer Token kostet nur die jti, nicht den Link")
    void unlesbarerToken() {
        when(tokenDienst.createToken(eq(7L), eq("plaintext"), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn("keinjwt");

        assertTrue(dienst.erzeuge().endsWith("?t=keinjwt"));
        verify(zustand).merkeHandyLink(null);
    }

    @Test
    @DisplayName("Abschalten widerruft den Token UND loescht das Kennzeichen")
    void abschalten() {
        when(tokenDienst.invalidateTokensByName(7L, "plaintext", WatchHandyLinkService.TOKEN_NAME))
                .thenReturn(1);

        assertEquals(1, dienst.widerrufe());

        verify(tokenDienst).invalidateTokensByName(7L, "plaintext", WatchHandyLinkService.TOKEN_NAME);
        verify(zustand).schalteHandyLinkAb();
    }

    @Test
    @DisplayName("Ohne Token-Modul wird nichts ausgestellt, statt still einen wertlosen Link zu liefern")
    void ohneTokenmodul() {
        dienst = new WatchHandyLinkService(zustand, sicherheit, liefert(null), liefert(null));

        assertFalse(dienst.verfuegbar());
        assertThrows(IllegalStateException.class, () -> dienst.erzeuge());
        verify(zustand, never()).merkeHandyLink(anyString());
    }

    @Test
    @DisplayName("Ohne angemeldeten Benutzer wird nichts ausgestellt — auch nicht im Cron-Kontext (-1)")
    void ohneBenutzer() {
        when(sicherheit.getId()).thenReturn(-1L);

        assertThrows(IllegalStateException.class, () -> dienst.erzeuge());
        verify(tokenDienst, never()).createToken(org.mockito.ArgumentMatchers.any(), anyString(),
                anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("Abschalten ohne Token-Modul loescht wenigstens das Kennzeichen")
    void abschaltenOhneTokenmodul() {
        dienst = new WatchHandyLinkService(zustand, sicherheit, liefert(null), liefert(null));

        assertEquals(0, dienst.widerrufe());
        verify(zustand).schalteHandyLinkAb();
    }
}
