/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.jsf;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Datei-Download aus einer JSF-Aktion heraus — EINE Implementierung statt zwoelf Kopien
 * (Massnahme 9, 29.08.2026).
 *
 * <p>Der Block „responseReset, Content-Type, Content-Length, Content-Disposition, Cache-Header,
 * write, responseComplete" stand in jeder Backing Bean mit Download noch einmal, jedes Mal mit
 * kleinen Abweichungen (Umlaute im Dateinamen, fehlendes {@code responseComplete}, fehlender
 * Cache-Header). Hier steht er einmal, inklusive RFC-5987-Dateiname ({@code filename*}), damit
 * „Beleg Müller.pdf" in jedem Browser so heisst.</p>
 *
 * <p>Ausserhalb einer JSF-Anfrage (kein {@link FacesContext}) passiert nichts — wie bei
 * {@link FacesMessages}.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class JsfDownload {

    private static final String OCTET_STREAM = "application/octet-stream";

    private JsfDownload() {
    }

    /** Als Anhang senden („Speichern unter"). */
    public static void send(byte[] daten, String contentType, String dateiname) {
        sende(daten, contentType, dateiname, "attachment");
    }

    /** Im Browser anzeigen (PDF, Bild), Dateiname fuer „Speichern unter" trotzdem gesetzt. */
    public static void sendInline(byte[] daten, String contentType, String dateiname) {
        sende(daten, contentType, dateiname, "inline");
    }

    private static void sende(byte[] daten, String contentType, String dateiname, String disposition) {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null) {
            return;
        }
        byte[] inhalt = daten == null ? new byte[0] : daten;
        String name = dateiname == null || dateiname.isBlank() ? "download" : dateiname.trim();
        ExternalContext ec = fc.getExternalContext();
        ec.responseReset();
        ec.setResponseContentType(contentType == null || contentType.isBlank() ? OCTET_STREAM : contentType);
        ec.setResponseContentLength(inhalt.length);
        ec.setResponseHeader("Content-Disposition", contentDisposition(disposition, name));
        ec.setResponseHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        ec.setResponseHeader("Pragma", "no-cache");
        ec.setResponseHeader("Expires", "0");
        try {
            ec.getResponseOutputStream().write(inhalt);
            ec.getResponseOutputStream().flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Download '" + name + "' konnte nicht geschrieben werden", e);
        }
        fc.responseComplete();
    }

    /**
     * {@code attachment; filename="ascii"; filename*=UTF-8''kodiert} — der ASCII-Teil fuer alte
     * Clients, der kodierte fuer alle, die RFC 5987 koennen (alle aktuellen Browser).
     */
    static String contentDisposition(String disposition, String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        String kodiert = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return disposition + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + kodiert;
    }
}
