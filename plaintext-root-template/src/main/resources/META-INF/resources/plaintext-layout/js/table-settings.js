/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Verhalten des Bedienbereichs einer Tabelle (META-INF/tags/tableSettings.xhtml): das Viereck
 * in jedem Spaltenkopf - ziehen aendert die Breite, ein Klick setzt die Spalte auf die
 * eingestellte Breite (Karte 1336: ein Viereck statt Anfasser plus Knopf).
 *
 * WARUM ES DEN KLICK UEBERHAUPT GIBT:
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
 * wo noch keiner steht, und verdrahtet jeden Anfasser nur einmal, sonst loeste seine eigene
 * Aenderung ihn erneut aus).
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

    /** Setzt die Spalte auf die eingestellte Breite - dasselbe wie frueher der Klick auf den ↔-Knopf. */
    function setzen(th, rahmen, remoteName) {
        var ziel = zielBreite(rahmen);
        if (ziel < MIN_BREITE) {
            return;
        }
        var melde = window[remoteName];
        if (typeof melde === 'function') {
            melde([{name: 'sp', value: kopfText(th)}, {name: 'px', value: ziel}]);
        }
    }

    /**
     * EIN Viereck statt zwei (Karte 1336). Bis dahin standen im Spaltenkopf zwei kleine Vierecke
     * nebeneinander: der Zieh-Anfasser von PrimeFaces und daneben der eigene ↔-Knopf. Jetzt
     * uebernimmt der Anfasser beides: ziehen aendert die Breite frei (PrimeFaces, unveraendert),
     * ein KLICK ohne Bewegung setzt die Spalte auf die eingestellte Spaltenbreite.
     *
     * Warum das ohne Eingriff in PrimeFaces geht: der Anfasser ist ein jQuery-UI-draggable. Das
     * beginnt erst nach einer Bewegung zu ziehen, ein reiner Klick bleibt ein Klick. Nach einem
     * echten Ziehen kommt trotzdem noch ein click-Ereignis an (die Maus wird ueber dem Anfasser
     * losgelassen) - deshalb die Wegmessung ab mousedown. Der Doppelklick gehoert weiter
     * PrimeFaces (Breite an den Inhalt anpassen); der verzoegerte Einzelklick weicht ihm aus.
     */
    function anfasserVerdrahten(anfasser, th, rahmen, remoteName) {
        if (anfasser.getAttribute('data-pt-setzen')) {
            return;
        }
        anfasser.setAttribute('data-pt-setzen', '1');
        anfasser.title = 'Ziehen: Breite frei einstellen. Klicken: auf die eingestellte Spaltenbreite setzen.';
        var startX = null;
        var wartet = null;

        // Kein preventDefault und kein stopPropagation hier - das Ziehen braucht mousedown.
        anfasser.addEventListener('mousedown', function (e) {
            startX = e.clientX;
        });

        anfasser.addEventListener('click', function (e) {
            // Der Kopf sortiert bei jedem Klick auf ein span - auch auf den Anfasser.
            e.preventDefault();
            e.stopPropagation();
            var gezogen = startX !== null && Math.abs(e.clientX - startX) > 3;
            startX = null;
            if (gezogen) {
                return;
            }
            if (wartet) {
                clearTimeout(wartet);
            }
            wartet = setTimeout(function () {
                wartet = null;
                setzen(th, rahmen, remoteName);
            }, 250);
        }, true);

        anfasser.addEventListener('dblclick', function () {
            if (wartet) {
                clearTimeout(wartet);
                wartet = null;
            }
        }, true);
    }

    /**
     * Rueckfall fuer Spalten OHNE PrimeFaces-Anfasser (Tabelle ohne resizableColumns): dann ist
     * das Viereck ein eigenes Element, das nur setzt. Es sieht aus wie der Anfasser, damit die
     * Bedienung auf jeder Seite gleich aussieht.
     */
    function knopfAnhaengen(th, rahmen, remoteName) {
        if (th.querySelector('.pt-tablesettings-setzen')) {
            return;
        }
        var knopf = document.createElement('span');
        knopf.className = 'pt-tablesettings-setzen';
        knopf.title = 'Diese Spalte auf die eingestellte Spaltenbreite setzen';
        th.appendChild(knopf);

        // Der Kopf traegt selbst Handler fuer das Sortieren. Ohne dieses Abfangen in der
        // Erfassungsphase sortiert ein Klick auf den Knopf zusaetzlich die Tabelle um.
        knopf.addEventListener('mousedown', function (e) {
            e.preventDefault();
            e.stopPropagation();
        }, true);

        knopf.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            setzen(th, rahmen, remoteName);
        }, true);
    }

    function kopfEinrichten(th, tabelle, rahmen, remoteName) {
        var anfasser = th.querySelector(':scope > .ui-column-resizer');
        if (anfasser) {
            // Ein Rueckfall-Knopf aus einem frueheren Durchlauf (bevor PrimeFaces die Anfasser
            // gesetzt hatte) waere jetzt das zweite Viereck - weg damit.
            var alt = th.querySelector(':scope > .pt-tablesettings-setzen');
            if (alt) {
                alt.parentNode.removeChild(alt);
            }
            anfasserVerdrahten(anfasser, th, rahmen, remoteName);
            return;
        }
        // Eine Tabelle mit resizableColumns bekommt ihre Anfasser vom PrimeFaces-Widget, je nach
        // Ladezeitpunkt erst nach diesem Durchlauf. Dann nicht vorgreifen: der Beobachter kommt
        // wieder, sobald sie da sind.
        if (tabelle.classList.contains('ui-datatable-resizable')) {
            return;
        }
        knopfAnhaengen(th, rahmen, remoteName);
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
            // Markierung fuer table-settings.css: unter dem Mobil-Umbruch setzt es die
            // gespeicherten Breiten dieser Tabelle ausser Kraft (Entscheid 2, Karte 1077).
            // Idempotent - nach jedem Teilupdate ist der Tabellenknoten neu, der Beobachter
            // setzt die Klasse dann wieder.
            tabelle.classList.add('pt-tablesettings-breiten');
            var koepfe = tabelle.querySelectorAll('thead th');
            for (var k = 0; k < koepfe.length; k++) {
                kopfEinrichten(koepfe[k], tabelle, r, remoteName);
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
