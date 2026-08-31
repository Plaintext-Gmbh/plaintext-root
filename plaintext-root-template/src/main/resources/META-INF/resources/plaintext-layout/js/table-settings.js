/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Verhalten des Bedienbereichs einer Tabelle (META-INF/tags/tableSettings.xhtml): der kleine
 * Setzen-Knopf in jedem Spaltenkopf, der eine Spalte auf die eingestellte Breite setzt.
 *
 * WARUM ES DIESEN KNOPF UEBERHAUPT GIBT:
 * Spalten von Hand zu ziehen trifft nie zweimal denselben Pixelwert. Wer drei Tabellen gleich
 * breit haben will, bekommt sie so nie gleich breit. Der Knopf setzt die Spalte auf eine ZAHL,
 * und Zahlen lassen sich wiederholen.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Solange irgendwo ein Inline-<script> steht, muss die Content-Security-Policy
 * script-src 'unsafe-inline' fuehren — und damit laeuft auch jedes eingeschleuste <script>.
 * Muster im Bestand: plaintext-layout/js/topbar.js.
 *
 * ANBINDUNG UEBER data-ATTRIBUTE STATT UEBER FESTE IDs:
 * Der Rahmen des Bedienbereichs traegt data-pt-tablesettings="<DOM-Id der Tabelle>" und
 * data-pt-remote="<Name des p:remoteCommand>". Damit weiss dieses Skript nichts ueber
 * Formularnamen — und zwei Bedienbereiche auf einer Seite stoeren sich nicht.
 *
 * WARUM EIN MutationObserver UND KEIN EINMALIGES EINRICHTEN:
 * Nach jedem PrimeFaces-Teilupdate ist der Tabellenkopf ein neuer DOM-Knoten; die Knoepfe waeren
 * weg. Der Beobachter setzt sie wieder — und ist gegen sich selbst gesichert (er fuegt nur ein,
 * wo noch keiner steht, sonst loeste seine eigene Aenderung ihn erneut aus).
 */
(function () {
    'use strict';

    var MIN_BREITE = 44;

    /** Der Kopftext, wie ihn TableSettings.keyFromHeader auf der Serverseite erwartet. */
    function kopfText(th) {
        var titel = th.querySelector('.ui-column-title');
        return ((titel ? titel.textContent : th.textContent) || '').trim();
    }

    /**
     * Zielbreite aus dem Feld im Bedienbereich. Bewusst aus dem DOM und nicht vom Bean: der Wert
     * kann eben erst eingetippt und noch nicht abgeschickt worden sein.
     *
     * p:inputNumber rendert zwei Felder — das sichtbare (_input, formatiert, mit " px") und das
     * versteckte (_hinput, roh). Beide werden akzeptiert; alles ausser Ziffern und Punkt faellt
     * vorher weg.
     */
    function zielBreite(rahmen) {
        var felder = rahmen.querySelectorAll(
            'input[id$="-targetWidth_input"], input[id$="-targetWidth_hinput"],'
            + ' .pt-tablesettings-zielbreite input, input.pt-tablesettings-zielbreite');
        for (var i = 0; i < felder.length; i++) {
            var roh = (felder[i].value || '').replace(/[^0-9.]/g, '');
            var w = parseFloat(roh);
            if (!isNaN(w)) {
                return Math.round(w);
            }
        }
        return 0;
    }

    function knopfAnhaengen(th, rahmen, remoteName) {
        if (th.querySelector('.pt-tablesettings-setzen')) {
            return;
        }
        var knopf = document.createElement('span');
        knopf.className = 'pt-tablesettings-setzen';
        knopf.textContent = '↔';
        knopf.title = 'Diese Spalte auf die eingestellte Spaltenbreite setzen';
        th.appendChild(knopf);

        // Der Kopf traegt selbst Handler fuer Sortieren und Ziehen. Ohne dieses Abfangen in der
        // Erfassungsphase sortiert ein Klick auf den Knopf zusaetzlich die Tabelle um.
        knopf.addEventListener('mousedown', function (e) {
            e.preventDefault();
            e.stopPropagation();
        }, true);

        knopf.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var ziel = zielBreite(rahmen);
            if (ziel < MIN_BREITE) {
                return;
            }
            var melde = window[remoteName];
            if (typeof melde === 'function') {
                melde([{name: 'sp', value: kopfText(th)}, {name: 'px', value: ziel}]);
            }
        }, true);
    }

    function einrichten() {
        var rahmen = document.querySelectorAll('[data-pt-tablesettings]');
        for (var i = 0; i < rahmen.length; i++) {
            var r = rahmen[i];
            var remoteName = r.getAttribute('data-pt-remote');
            if (!remoteName) {
                continue;   // Seite ohne Spaltenbreiten - dann gibt es nichts zu setzen.
            }
            var tabelle = document.getElementById(r.getAttribute('data-pt-tablesettings'));
            if (!tabelle) {
                continue;   // Tabelle (noch) nicht im DOM, z.B. ein Reiter, der zu ist.
            }
            var koepfe = tabelle.querySelectorAll('thead th');
            for (var k = 0; k < koepfe.length; k++) {
                knopfAnhaengen(koepfe[k], r, remoteName);
            }
        }
    }

    function sicherEinrichten() {
        try {
            einrichten();
        } catch (e) {
            // Der Beobachter laeuft bei jeder DOM-Aenderung; eine Ausnahme hier schluege sonst
            // mitten in ein PrimeFaces-Teilupdate hinein.
            if (window.console) {
                console.warn('[pt:tableSettings]', e);
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', sicherEinrichten);
    } else {
        sicherEinrichten();
    }
    new MutationObserver(sicherEinrichten).observe(document.body, {childList: true, subtree: true});
})();
