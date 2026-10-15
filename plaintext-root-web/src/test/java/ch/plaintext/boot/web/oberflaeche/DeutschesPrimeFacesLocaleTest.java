/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.oberflaeche;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card 1547: every app renders PrimeFaces in German. The view locale comes from the default locale in this
 * module's faces-config.xml; from it PrimeFaces picks its server texts ({@code org.primefaces.Messages_de}) and the
 * client script {@code locales/locale-<lang>.js}. Before, the JVM default ({@code en}) applied: "No records found.",
 * "Ascending".
 */
class DeutschesPrimeFacesLocaleTest {

    @Test
    void facesConfigSetztDeutschAlsStandard_ohneSupportedLocales() throws Exception {
        Document doc;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/faces-config.xml")) {
            assertThat(in).as("faces-config.xml dieses Moduls").isNotNull();
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            doc = f.newDocumentBuilder().parse(in);
        }
        NodeList standard = doc.getElementsByTagNameNS("*", "default-locale");
        assertThat(standard.getLength()).isEqualTo(1);
        assertThat(standard.item(0).getTextContent().trim()).isEqualTo("de");
        // schuetu declares its languages itself; a list here would compete with it
        assertThat(doc.getElementsByTagNameNS("*", "supported-locale").getLength()).isZero();
    }

    @Test
    void primeFacesLoestDieSchluesselDeutschAuf() {
        ResourceBundle de = ResourceBundle.getBundle("org.primefaces.Messages", Locale.GERMAN);
        assertThat(de.getString("primefaces.data.EMPTY_MESSAGE")).isEqualTo("Keine Datensätze gefunden.");
        assertThat(de.getString("primefaces.datatable.SORT_ASC")).isEqualTo("Aufsteigend");
        // Gegenprobe: ohne deutsches Locale stehen genau die englischen Texte der Seiten
        ResourceBundle en = ResourceBundle.getBundle("org.primefaces.Messages", Locale.ROOT);
        assertThat(en.getString("primefaces.data.EMPTY_MESSAGE")).isEqualTo("No records found.");
    }

    @Test
    void clientLocaleFuerDeutschLiegtBei() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/resources/primefaces/locales/locale-de.js")) {
            assertThat(in).isNotNull();
            String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(js).contains("Keine Einträge gefunden").contains("Erste Seite");
        }
    }
}
