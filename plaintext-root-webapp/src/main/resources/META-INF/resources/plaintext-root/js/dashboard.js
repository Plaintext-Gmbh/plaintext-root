/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Dashboard (index.xhtml):
 *  1. die Auswahlliste einer Kachel springt auf die gewaehlte Adresse;
 *  2. Karte 1351: im Edit-Modus Kacheln ziehen, mit Pfeilen verschieben, ein-/ausblenden und
 *     auf halbe/volle Breite stellen.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Das stand als onchange="if(this.value){window.location.href=this.value;}" am <select> — ein
 * echter HTML-Inline-Handler und fuer den Browser dasselbe wie ein Inline-<script>. Solange auch
 * nur einer existiert, muss die Content-Security-Policy script-src 'unsafe-inline' fuehren, und
 * dann laeuft auch jedes eingeschleuste <script>. Aus demselben Grund kein eval, kein
 * new Function, keine Handler-Attribute im Edit-Modus.
 *
 * Gebunden wird per Delegation am document: die Kacheln stehen in einem <ui:repeat> innerhalb
 * eines <h:form> und werden per Ajax neu gerendert ("Anpassen", "Abbrechen") — ein Zuhoerer am
 * document ueberlebt das, Zuhoerer an den einzelnen Elementen nicht.
 *
 * EDIT-MODUS: Der ganze Zustand steht im DOM (Reihenfolge der Zellen, data-sichtbar, data-halb).
 * Nach jeder Aenderung schreibt schreibeAnordnung() ihn in das versteckte Feld fm:anordnung,
 * Aufbau "id:sichtbar:halb,..." wie StartseitenAnordnung.zuFormular auf dem Server. Gespeichert
 * wird erst mit "Speichern" — ein halb fertiges Umordnen landet nie in der Datenbank.
 *
 * Ziehen ist natives HTML5-Drag-and-Drop. Touch-Geraete loesen das nicht zuverlaessig aus; dafuer
 * gibt es die Pfeile, die auch per Tastatur erreichbar sind.
 */
(function () {
    'use strict';

    var GRID_ID = 'dashboard-grid';
    var FELD_ID = 'fm:anordnung';

    document.addEventListener('change', function (e) {
        var el = e.target;
        if (!el || !el.classList) {
            return;
        }
        if (el.classList.contains('dashboard-dropdown') && el.value) {
            window.location.href = el.value;
            return;
        }
        var zelle = zelleVon(el);
        if (!zelle) {
            return;
        }
        if (el.classList.contains('dashboard-schalter-sichtbar')) {
            zelle.setAttribute('data-sichtbar', el.checked ? '1' : '0');
            zelle.classList.toggle('dashboard-ausgeblendet', !el.checked);
            schreibeAnordnung();
        } else if (el.classList.contains('dashboard-schalter-halb')) {
            zelle.setAttribute('data-halb', el.checked ? '1' : '0');
            zelle.classList.toggle('dashboard-halb', el.checked);
            zelle.classList.toggle('dashboard-voll', !el.checked);
            schreibeAnordnung();
        }
    });

    document.addEventListener('click', function (e) {
        var knopf = e.target && e.target.closest ? e.target.closest('.dashboard-pfeil') : null;
        if (!knopf) {
            return;
        }
        var zelle = zelleVon(knopf);
        if (!zelle) {
            return;
        }
        e.preventDefault();
        if (knopf.getAttribute('data-aktion') === 'hoch') {
            var vorher = vorigeZelle(zelle);
            if (vorher) {
                zelle.parentNode.insertBefore(zelle, vorher);
            }
        } else {
            var nachher = naechsteZelle(zelle);
            if (nachher) {
                zelle.parentNode.insertBefore(nachher, zelle);
            }
        }
        knopf.focus();
        schreibeAnordnung();
    });

    // ── Ziehen ────────────────────────────────────────────────────────────
    var gezogen = null;

    document.addEventListener('dragstart', function (e) {
        var zelle = bearbeitbareZelle(e.target);
        if (!zelle) {
            return;
        }
        gezogen = zelle;
        zelle.classList.add('dashboard-zieht');
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
            // Firefox startet das Ziehen nur mit gesetzten Daten.
            e.dataTransfer.setData('text/plain', zelle.getAttribute('data-kachel') || '');
        }
    });

    document.addEventListener('dragover', function (e) {
        if (!gezogen) {
            return;
        }
        var ziel = bearbeitbareZelle(e.target);
        if (!ziel || ziel === gezogen) {
            return;
        }
        e.preventDefault();
        if (e.dataTransfer) {
            e.dataTransfer.dropEffect = 'move';
        }
        markiereZiel(ziel);
    });

    document.addEventListener('drop', function (e) {
        if (!gezogen) {
            return;
        }
        var ziel = bearbeitbareZelle(e.target);
        if (ziel && ziel !== gezogen) {
            e.preventDefault();
            // Vor das Ziel, wenn in dessen erster Haelfte losgelassen, sonst dahinter. Halbe
            // Kacheln stehen nebeneinander (links/rechts), volle untereinander (oben/unten).
            var r = ziel.getBoundingClientRect();
            var davor = ziel.classList.contains('dashboard-halb')
                ? (e.clientX - r.left) < r.width / 2
                : (e.clientY - r.top) < r.height / 2;
            if (davor) {
                ziel.parentNode.insertBefore(gezogen, ziel);
            } else {
                ziel.parentNode.insertBefore(gezogen, ziel.nextSibling);
            }
            schreibeAnordnung();
        }
        aufraeumen();
    });

    document.addEventListener('dragend', aufraeumen);

    function aufraeumen() {
        if (gezogen) {
            gezogen.classList.remove('dashboard-zieht');
        }
        gezogen = null;
        markiereZiel(null);
    }

    function markiereZiel(ziel) {
        var alle = document.querySelectorAll('#' + GRID_ID + ' .dashboard-ziel');
        for (var i = 0; i < alle.length; i++) {
            if (alle[i] !== ziel) {
                alle[i].classList.remove('dashboard-ziel');
            }
        }
        if (ziel) {
            ziel.classList.add('dashboard-ziel');
        }
    }

    // ── Hilfen ────────────────────────────────────────────────────────────
    function grid() {
        return document.getElementById(GRID_ID);
    }

    function imEditModus() {
        var g = grid();
        return !!g && g.getAttribute('data-bearbeiten') === 'true';
    }

    function zelleVon(el) {
        if (!imEditModus() || !el || !el.closest) {
            return null;
        }
        return el.closest('#' + GRID_ID + ' .dashboard-zelle');
    }

    function bearbeitbareZelle(el) {
        if (el && el.nodeType !== 1) {
            el = el.parentElement;
        }
        return zelleVon(el);
    }

    function vorigeZelle(zelle) {
        var el = zelle.previousElementSibling;
        while (el && !el.classList.contains('dashboard-zelle')) {
            el = el.previousElementSibling;
        }
        return el;
    }

    function naechsteZelle(zelle) {
        var el = zelle.nextElementSibling;
        while (el && !el.classList.contains('dashboard-zelle')) {
            el = el.nextElementSibling;
        }
        return el;
    }

    function schreibeAnordnung() {
        var feld = document.getElementById(FELD_ID);
        var g = grid();
        if (!feld || !g) {
            return;
        }
        var teile = [];
        var zellen = g.querySelectorAll('.dashboard-zelle');
        for (var i = 0; i < zellen.length; i++) {
            var id = zellen[i].getAttribute('data-kachel');
            if (!id) {
                continue;
            }
            teile.push(encodeURIComponent(id) + ':' + zellen[i].getAttribute('data-sichtbar')
                + ':' + zellen[i].getAttribute('data-halb'));
        }
        feld.value = teile.join(',');
    }
})();
