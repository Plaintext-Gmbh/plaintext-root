/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Secrets-Verwaltung (secrets.xhtml): das generierte Passwort in die Zwischenablage kopieren und
 * das kurz als "kopiert" quittieren.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Das stand als mehrzeiliges onclick="..." am <button> — ein echter HTML-Inline-Handler und fuer
 * den Browser dasselbe wie ein Inline-<script>. Solange auch nur einer existiert, muss die
 * Content-Security-Policy script-src 'unsafe-inline' fuehren, und dann laeuft auch jedes
 * eingeschleuste <script>.
 *
 * Das Feld, aus dem kopiert wird, steht als Client-Id im data-Attribut des Knopfs
 * (data-pt-copy-target). Gebunden wird per Delegation am document, weil der Knopf in
 * <h:form id="fm"> liegt und mit dem Formular per Ajax neu gerendert werden kann.
 */
(function () {
    'use strict';

    var QUITTUNG_MS = 1200;

    document.addEventListener('click', function (e) {
        var ziel = e.target instanceof Element ? e.target : null;
        var knopf = ziel ? ziel.closest('[data-pt-copy-target]') : null;
        if (!knopf) {
            return;
        }
        e.preventDefault();
        var feld = document.getElementById(knopf.getAttribute('data-pt-copy-target'));
        if (!feld || !feld.value) {
            return;
        }
        navigator.clipboard.writeText(feld.value);
        var vorher = knopf.innerHTML;
        knopf.innerHTML = ' kopiert ✓';
        setTimeout(function () {
            knopf.innerHTML = vorher;
        }, QUITTUNG_MS);
    });
})();
