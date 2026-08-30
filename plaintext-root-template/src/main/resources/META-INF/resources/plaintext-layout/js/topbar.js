/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Verhalten der Topbar (includes/topbar.xhtml): die drei Aufklapp-Menues (Sprache,
 * Benachrichtigungen, Benutzer) und das Abmelden per XHR.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Hier standen VIER Inline-<script>-Bloecke, drei davon fast wortgleich. Solange auch nur ein
 * Inline-Block existiert, muss die Content-Security-Policy script-src 'unsafe-inline' fuehren —
 * und damit laeuft auch jedes eingeschleuste <script>: die CSP ist dann kein XSS-Schutz mehr.
 * Muster im Bestand: plaintext-layout/js/config.js.
 *
 * ANBINDUNG UEBER data-ATTRIBUTE STATT UEBER FESTE IDs IM SKRIPT:
 * Der Ausloeser traegt data-pt-dropdown="<id des Menues>"; wer ein viertes Aufklapp-Menue
 * ergaenzt, fasst diese Datei nicht mehr an. data-pt-dropdown-align="edge" schaltet zusaetzlich
 * die Randkorrektur zu (das Benutzermenue haengt rechts aussen und lief sonst aus dem Fenster).
 *
 * DELEGATION STATT DIREKTER BINDUNG — das behebt zugleich einen Altfehler:
 * Das Benachrichtigungs-Menue liegt in <h:form id="notifFm">, und darin steht ein
 * <p:poll interval="60" update="@form"/>. Jede Minute ersetzt PrimeFaces also Glocke und
 * Aufklappliste durch neue Elemente. Die alten Inline-Bloecke haben ihre Zuhoerer EINMAL an die
 * damals vorhandenen Knoten gehaengt — nach dem ersten Poll zeigte ein Klick auf die Glocke
 * nichts mehr. Ein Zuhoerer am document ueberlebt jedes Ajax-Update.
 */
(function () {
    'use strict';

    /** Das zu einem Ausloeser gehoerende Aufklappmenue (oder null). */
    function menueVon(ausloeser) {
        return document.getElementById(ausloeser.getAttribute('data-pt-dropdown'));
    }

    function randkorrekturAnwenden(dd) {
        // Erst links ausrichten, dann messen: ragt das Menue rechts aus dem Fenster, um genau
        // diesen Betrag (plus 8px Luft) nach links ziehen.
        dd.style.left = '0';
        dd.style.right = 'auto';
        var r = dd.getBoundingClientRect();
        if (r.right > window.innerWidth) {
            dd.style.left = (-(r.right - window.innerWidth + 8)) + 'px';
        }
    }

    document.addEventListener('click', function (e) {
        var ziel = e.target instanceof Element ? e.target : null;
        var ausloeser = ziel ? ziel.closest('[data-pt-dropdown]') : null;

        // Alles zuklappen, was weder gerade umgeschaltet wird noch den Klick enthaelt
        // (ein Klick IM Menue — etwa auf "Als gelesen markieren" — darf es nicht schliessen).
        document.querySelectorAll('[data-pt-dropdown]').forEach(function (b) {
            var dd = menueVon(b);
            if (dd && b !== ausloeser && !dd.contains(e.target)) {
                dd.style.display = 'none';
            }
        });

        if (!ausloeser) {
            return;
        }
        var dd = menueVon(ausloeser);
        if (!dd) {
            return;
        }
        e.preventDefault();
        var oeffnen = dd.style.display === 'none';
        dd.style.display = oeffnen ? 'block' : 'none';
        if (oeffnen && ausloeser.getAttribute('data-pt-dropdown-align') === 'edge') {
            randkorrekturAnwenden(dd);
        }
    });

    /*
     * Abmelden per XHR statt per Formular-Submit, damit die Antwort ausgewertet werden kann:
     * bei Erfolg folgt der XHR dem 302 und responseURL zeigt auf /login.html. Bei einem Fehler
     * (z.B. 403) bleibt responseURL die /logout-URL — dorthin darf NICHT navigiert werden,
     * GET /logout ist 404. Dann geht es auf die Anmeldeseite.
     */
    document.addEventListener('click', function (e) {
        var ziel = e.target instanceof Element ? e.target : null;
        var link = ziel ? ziel.closest('[data-pt-logout-form]') : null;
        if (!link) {
            return;
        }
        var form = document.getElementById(link.getAttribute('data-pt-logout-form'));
        if (!form) {
            return;
        }
        e.preventDefault();
        var xhr = new XMLHttpRequest();
        xhr.open('POST', form.action, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.onreadystatechange = function () {
            if (xhr.readyState === 4) {
                var loginUrl = form.action.replace('/logout', '/login.html');
                var ok = xhr.status >= 200 && xhr.status < 400
                         && xhr.responseURL && xhr.responseURL.indexOf('/logout') === -1;
                window.location.href = ok ? xhr.responseURL : loginUrl;
            }
        };
        var csrf = form.querySelector('input[name="_csrf"]');
        xhr.send('_csrf=' + encodeURIComponent(csrf ? csrf.value : ''));
    });
})();
