/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Langer Druck auf den Vorwaerts-Knopf fuehrt zur Uebersicht (Karte 1276).
 *
 * WARUM EINE DATEI UND KEIN onclick: PlaintextInlineJsVertragTest verbietet Inline-JavaScript
 * in XHTML, damit die CSP ohne script-src 'unsafe-inline' auskommt. Solange auch nur ein
 * Inline-Block existiert, laeuft auch jedes eingeschleuste <script>.
 *
 * WARUM POINTER-EREIGNISSE UND NICHT touchstart/mousedown: die Uhr wird mit dem Finger bedient,
 * die Telefonansicht oft auch, und am Rechner steht eine Maus. Pointer-Ereignisse decken alle
 * drei ab, ohne dass man dasselbe dreimal verdrahtet und sich Doppelausloesungen einhandelt
 * (ein Tippen erzeugt sonst touchstart UND mousedown).
 */
(function () {
    'use strict';

    var SCHWELLE_MS = 550;   // kuerzer wirkt wie ein verrutschter Tipper, laenger fuehlt sich kaputt an

    function verdrahte(knopf) {
        var ziel = knopf.getAttribute('data-lang-ziel');
        if (!ziel) {
            return;   // keine Uebersicht vorhanden: der Knopf bleibt ein reiner Vorwaerts-Knopf
        }

        var uhr = null;
        var langGedrueckt = false;

        function abbrechen() {
            if (uhr !== null) {
                window.clearTimeout(uhr);
                uhr = null;
            }
        }

        knopf.addEventListener('pointerdown', function () {
            langGedrueckt = false;
            abbrechen();
            uhr = window.setTimeout(function () {
                uhr = null;
                langGedrueckt = true;
                // Kurze Rueckmeldung, sonst weiss niemand, ob der lange Druck gezaehlt hat.
                if (window.navigator && typeof window.navigator.vibrate === 'function') {
                    window.navigator.vibrate(15);
                }
                window.location.assign(ziel);
            }, SCHWELLE_MS);
        });

        ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (name) {
            knopf.addEventListener(name, abbrechen);
        });

        // Der Knopf schickt das Formular ab. Nach einem langen Druck darf das NICHT passieren,
        // sonst blaettert die Uhr zusaetzlich eine Seite weiter, waehrend sie zur Uebersicht
        // wechselt — und welche der beiden Adressen gewinnt, entscheidet der Zufall.
        knopf.addEventListener('click', function (e) {
            if (langGedrueckt) {
                langGedrueckt = false;
                e.preventDefault();
                e.stopPropagation();
            }
        });
    }

    function start() {
        var knoepfe = document.querySelectorAll('[data-lang-ziel]');
        for (var i = 0; i < knoepfe.length; i++) {
            verdrahte(knoepfe[i]);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
}());
