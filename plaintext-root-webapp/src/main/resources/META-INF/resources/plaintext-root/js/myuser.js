/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Eigenes Profil (myuser.xhtml): Ctrl+Shift+D schaltet den Entwickler-Modus um. Der
 * eigentliche Umschalter ist ein <p:remoteCommand name="remoteToggleAdvancedMode">.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Der Code stand als Inline-<script> in der Seite. Solange auch nur ein Inline-Block existiert,
 * muss die Content-Security-Policy script-src 'unsafe-inline' fuehren — und damit laeuft auch
 * jedes eingeschleuste <script>: die CSP ist dann kein XSS-Schutz mehr, sondern Dekoration.
 * Muster im Bestand: plaintext-layout/js/config.js.
 */

// Advanced mode toggle via Ctrl+Shift+D
document.addEventListener('keydown', function(event) {
    if (event.ctrlKey && event.shiftKey && event.key === 'D') {
        event.preventDefault();
        // Der p:remoteCommand definiert die Funktion global, aber erst wenn sein Formular
        // gerendert ist. Vor dem Auslagern lief dieser Block inline nach dem Formular; als
        // externe Datei kann die Reihenfolge abweichen, darum die Pruefung.
        if (typeof remoteToggleAdvancedMode === 'function') {
            remoteToggleAdvancedMode();
        }
    }
});
