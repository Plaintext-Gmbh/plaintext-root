/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Ersetzt die Adresse in der Adresszeile durch eine sprechende URL, ohne neu zu laden — fuer
 * Seiten, die per POST/Navigation erreicht werden und sonst eine technische Adresse anzeigen
 * (sessioninsights.xhtml, includes/entityverwaltung.xhtml).
 *
 * Die Ziel-URL kommt als data-Attribut am Markierungselement, NICHT als EL im Skriptkoerper:
 *   <div data-pt-pushstate="#{pageUrl}" hidden="hidden"/>
 *   <h:outputScript library="plaintext-layout" name="js/pushstate.js"/>
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Beide Seiten hatten dafuer ein <h:outputScript> MIT Rumpf — das rendert einen Inline-Block
 * und zwingt die Content-Security-Policy zu script-src 'unsafe-inline'.
 */
(function () {
    'use strict';

    function anwenden() {
        var el = document.querySelector('[data-pt-pushstate]');
        if (!el) {
            return;
        }
        var url = el.getAttribute('data-pt-pushstate');
        if (!url) {
            return;
        }
        try {
            history.pushState(null, null, url);
        } catch (e) {
            // Fremde Herkunft oder abgeschaltete History-API: die Adresszeile bleibt, wie sie ist.
            console.debug('pushState nicht moeglich:', e);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', anwenden);
    } else {
        anwenden();
    }
})();
