/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Dashboard (index.xhtml): die Auswahlliste einer Kachel springt auf die gewaehlte Adresse.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Das stand als onchange="if(this.value){window.location.href=this.value;}" am <select> — ein
 * echter HTML-Inline-Handler und fuer den Browser dasselbe wie ein Inline-<script>. Solange auch
 * nur einer existiert, muss die Content-Security-Policy script-src 'unsafe-inline' fuehren, und
 * dann laeuft auch jedes eingeschleuste <script>.
 *
 * Gebunden wird per Delegation am document: die Kacheln stehen in einem <ui:repeat> innerhalb
 * eines <h:form> und koennen per Ajax neu gerendert werden — ein Zuhoerer am document ueberlebt
 * das, Zuhoerer an den einzelnen Elementen nicht.
 */
(function () {
    'use strict';

    document.addEventListener('change', function (e) {
        var el = e.target;
        if (el && el.classList && el.classList.contains('dashboard-dropdown') && el.value) {
            window.location.href = el.value;
        }
    });
})();
