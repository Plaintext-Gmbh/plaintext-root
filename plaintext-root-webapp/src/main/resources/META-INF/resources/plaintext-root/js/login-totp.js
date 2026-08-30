/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Zweiter Faktor (login-totp.xhtml): Hell-/Dunkelmodus aus dem Cookie bzw. der
 * Systemeinstellung setzen, bevor die Seite sichtbar wird, und der Abbrechen-Link.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Der Code stand als Inline-<script> in der Seite. Solange auch nur ein Inline-Block existiert,
 * muss die Content-Security-Policy script-src 'unsafe-inline' fuehren — und damit laeuft auch
 * jedes eingeschleuste <script>: die CSP ist dann kein XSS-Schutz mehr, sondern Dekoration.
 * Muster im Bestand: plaintext-layout/js/config.js.
 */

function initTheme() {
    var m = document.cookie.match(/plaintext-theme=(\w+)/);
    var theme = m ? m[1] : (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', theme);
}
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTheme);
} else { initTheme(); }

// Abbrechen: der Link postet das versteckte Abmelde-Formular. Frueher stand das als
// onclick="event.preventDefault(); document.getElementById('abbrechen-form').submit();"
// am <a> — ein echter HTML-Inline-Handler, fuer den Browser dasselbe wie ein Inline-<script>.
document.addEventListener('DOMContentLoaded', function () {
    var link = document.getElementById('abbrechen-link');
    var form = document.getElementById('abbrechen-form');
    if (!link || !form) {
        return;
    }
    link.addEventListener('click', function (e) {
        e.preventDefault();
        form.submit();
    });
});
