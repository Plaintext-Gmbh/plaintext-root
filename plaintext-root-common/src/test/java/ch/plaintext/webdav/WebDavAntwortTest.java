/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.webdav;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Karte 1386: die WebDAV-Antwort wird ohne DOCTYPE gelesen (XXE). */
class WebDavAntwortTest {

    @Test
    @DisplayName("Positivkontrolle: Ordner und Datei mit Grösse werden erkannt")
    void lesen() throws IOException {
        var l = WebDavAntwort.lies("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/a/</d:href><d:propstat><d:prop>"
                + "<d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"
                + "<d:response><d:href>/a/b%20c.jpg</d:href><d:propstat><d:prop><d:resourcetype/>"
                + "<d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>");
        assertThat(l).hasSize(2);
        assertThat(l.get(0).ordner()).isTrue();
        assertThat(l.get(1).pfad()).isEqualTo("/a/b c.jpg");
        assertThat(l.get(1).groesse()).isEqualTo(42);
    }

    @Test
    @DisplayName("Eine Antwort mit DOCTYPE/Entity wird abgewiesen")
    void xxe() {
        String boese = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&e;</d:href></d:response></d:multistatus>";
        assertThatThrownBy(() -> WebDavAntwort.lies(boese)).isInstanceOf(IOException.class);
    }
}
