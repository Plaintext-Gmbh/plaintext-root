/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Cron-Verwaltung (cron.xhtml): den globalen Blockier-Schleier waehrend des Seitenaufbaus
 * zeigen und nach dem vollstaendigen Laden wieder ausblenden.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Der Code stand als Inline-<script> in der Seite. Solange auch nur ein Inline-Block existiert,
 * muss die Content-Security-Policy script-src 'unsafe-inline' fuehren — und damit laeuft auch
 * jedes eingeschleuste <script>: die CSP ist dann kein XSS-Schutz mehr, sondern Dekoration.
 * Muster im Bestand: plaintext-layout/js/config.js.
 */

window.addEventListener('load', function() {
    if (typeof PF === 'function' && PF('blockIt')) {
        PF('blockIt').hide();
    }
});

document.addEventListener('DOMContentLoaded', function() {
    if (typeof PF === 'function' && PF('blockIt')) {
        PF('blockIt').show();
    }
});
