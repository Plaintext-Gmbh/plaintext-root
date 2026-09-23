/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.jsf;

import ch.plaintext.arch.StabileApi;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;

/**
 * Central helper for user-facing JSF messages — Phase 1 of the JSF refactoring
 * (Daniel, 28.08.2026; see {@code docs/JSF_REFACTORING.md}).
 *
 * <p>Replaces the ~39 private {@code addMessage()} copies and ~124 direct
 * {@code FacesContext.getCurrentInstance().addMessage(...)} calls across the apps.
 * Beyond deduplication this is the SEAM for any later UI migration: code that only
 * calls this helper has no other compile-time dependency on JSF messaging.</p>
 *
 * <p>Null-safe outside a JSF request (e.g. in tests or background jobs): without a
 * current {@link FacesContext} the call is a silent no-op — exactly what the existing
 * copies did.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@StabileApi("Benutzermeldungen aus Backing-Beans")
public final class FacesMessages {

    private FacesMessages() {
    }

    public static void info(String text) {
        add(FacesMessage.SEVERITY_INFO, text, null);
    }

    public static void info(String text, String detail) {
        add(FacesMessage.SEVERITY_INFO, text, detail);
    }

    /**
     * Titel der Meldungen, die in den Backing Beans immer wieder gleich lauten (Karte 1221,
     * Sonar java:S1192 — „Define a constant instead of duplicating this literal").
     *
     * <p>Sie stehen hier und nicht je Bean, weil sie <b>dieselbe</b> Zeichenkette sind: drei
     * eigene Konstanten in drei Klassen waeren derselbe Fehler, nur dreimal kleiner. Wer die
     * Beschriftung aendern will, aendert sie an einer Stelle.
     */
    public static final String TITEL_FEHLER = "Fehler";

    /** Gegenstueck zu {@link #TITEL_FEHLER} fuer die Erfolgsmeldung. */
    public static final String TITEL_ERFOLG = "Erfolg";

    public static void warn(String text) {
        add(FacesMessage.SEVERITY_WARN, text, null);
    }

    public static void warn(String text, String detail) {
        add(FacesMessage.SEVERITY_WARN, text, detail);
    }

    public static void error(String text) {
        add(FacesMessage.SEVERITY_ERROR, text, null);
    }

    public static void error(String text, String detail) {
        add(FacesMessage.SEVERITY_ERROR, text, detail);
    }

    /**
     * Arbitrary severity as a parameter — for the {@code addMessage(severity, text)} wrappers that
     * sit in many backing beans (measure 9, 29.08.2026).
     */
    public static void meldung(FacesMessage.Severity severity, String text, String detail) {
        add(severity, text, detail);
    }

    /**
     * Message attached to a component ({@code clientId}, e.g. {@code "fm:betrag"}) — appears in
     * {@code <p:message for=…>} instead of the growl. {@code clientId == null} = global message.
     */
    public static void feld(String clientId, FacesMessage.Severity severity, String text, String detail) {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.addMessage(clientId, new FacesMessage(severity, text, detail));
        }
    }

    private static void add(FacesMessage.Severity severity, String text, String detail) {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.addMessage(null, new FacesMessage(severity, text, detail));
        }
    }
}
