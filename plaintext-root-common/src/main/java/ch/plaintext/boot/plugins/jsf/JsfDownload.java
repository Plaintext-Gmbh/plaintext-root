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
 * File download from within a JSF action — ONE implementation instead of twelve copies
 * (measure 9, 29.08.2026).
 *
 * <p>The block "responseReset, Content-Type, Content-Length, Content-Disposition, cache headers,
 * write, responseComplete" was repeated in every backing bean with a download, each time with
 * small deviations (umlauts in the file name, a missing {@code responseComplete}, a missing
 * cache header). Here it exists once, including the RFC 5987 file name ({@code filename*}), so
 * that "Beleg Müller.pdf" is named that way in every browser.</p>
 *
 * <p>Outside a JSF request (no {@link FacesContext}) nothing happens — just as in
 * {@link FacesMessages}.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class JsfDownload {

    private static final String OCTET_STREAM = "application/octet-stream";

    private JsfDownload() {
    }

    /** Send as an attachment ("Save as"). */
    public static void send(byte[] daten, String contentType, String dateiname) {
        sende(daten, contentType, dateiname, "attachment");
    }

    /** Display in the browser (PDF, image); the file name for "Save as" is set nonetheless. */
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
     * {@code attachment; filename="ascii"; filename*=UTF-8''kodiert} — the ASCII part for old
     * clients, the encoded one for everything that speaks RFC 5987 (all current browsers).
     */
    static String contentDisposition(String disposition, String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        String kodiert = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return disposition + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + kodiert;
    }
}
