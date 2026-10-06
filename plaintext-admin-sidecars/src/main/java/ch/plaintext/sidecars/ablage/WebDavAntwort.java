/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.ablage;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Liest eine WebDAV-{@code multistatus}-Antwort (PROPFIND). Übernommen aus plaintext-z-fotos (Karte 1386)
 * für die Speicher-Ablagen (Karte 1406).
 *
 * <p>Der Parser lässt keine DOCTYPE-Deklaration zu und löst keine externen Entitäten auf (XXE):
 * die Antwort kommt von einem Server, den der Benutzer selbst eingetragen hat.</p>
 */
final class WebDavAntwort {

    /**
     * Ein Eintrag der Antwort.
     *
     * @param pfad      dekodierter absoluter Pfad aus {@code href}
     * @param ordner    {@code true} bei einer Collection
     * @param groesse   {@code getcontentlength}, -1 wenn fehlt
     * @param geaendert {@code getlastmodified}, {@code null} wenn fehlt
     */
    record Eintrag(String pfad, boolean ordner, long groesse, Instant geaendert) {
    }

    private WebDavAntwort() {
    }

    static List<Eintrag> lies(String xml) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            doc = b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IOException("Unerwartete WebDAV-Antwort.", e);
        }
        List<Eintrag> out = new ArrayList<>();
        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Eintrag e = eintrag((Element) responses.item(i));
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** Ein {@code response}-Element; {@code null} ohne brauchbares {@code href} (Karte 1416, S3776/S135). */
    private static Eintrag eintrag(Element r) {
        String href = text(r, "href");
        if (href == null) {
            return null;
        }
        String pfad;
        try {
            pfad = URI.create(href.trim()).getPath();
        } catch (IllegalArgumentException _) {
            return null;
        }
        boolean ordner = r.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
        return new Eintrag(pfad, ordner, groesse(text(r, "getcontentlength")), geaendert(text(r, "getlastmodified")));
    }

    private static long groesse(String len) {
        if (len == null) {
            return -1;
        }
        try {
            return Long.parseLong(len.trim());
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private static Instant geaendert(String lm) {
        if (lm == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(lm.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String text(Element e, String local) {
        NodeList l = e.getElementsByTagNameNS("DAV:", local);
        if (l.getLength() == 0) {
            return null;
        }
        Node n = l.item(0);
        String t = n.getTextContent();
        return t == null || t.isBlank() ? null : t;
    }
}
