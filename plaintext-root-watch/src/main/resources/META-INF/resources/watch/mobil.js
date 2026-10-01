/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Mobil-Framework (Karten 1355/1387), das einzige Skript unter /watch/m/. Formulare mit
 * data-mobil gehen per fetch, die JSON-Antwort bringt Inhalt und Statuszeile. Ohne Skript
 * bleiben es echte POST-Formulare (303). CSP: kein Inline-Skript, kein eval; alles haengt an
 * data-Attributen und Ereignissen auf document. Siehe WATCH-UI-GUIDE.md, Abschnitt 7.
 */
(function () {
    'use strict';

    var SCHWELLE_MS = 550;   // wie watch.js: kuerzer wirkt wie ein verrutschter Tipper

    function el(id) {
        return document.getElementById(id);
    }

    function melde(text, fehler) {
        var m = el('m-meldung');
        if (!m) { return; }
        m.textContent = text || '';
        m.hidden = !text;
        m.classList.toggle('w-card-warn', !!fehler);
    }

    // ── Laufende Zeit (data-laeuft = Sekunden beim Zeichnen): zaehlt ab dem Eintreffen
    // weiter, nicht ab der Uhr des Telefons — die darf falsch gehen.
    function ticke() {
        var l = document.querySelectorAll('[data-laeuft]');
        for (var i = 0; i < l.length; i++) {
            var e = l[i];
            e.t0 = e.t0 || Date.now();
            var s = Number(e.getAttribute('data-laeuft')) + Math.floor((Date.now() - e.t0) / 1000);
            var m = Math.floor(s / 60);
            var t = Math.floor(m / 60) + ':' + ('0' + (m % 60)).slice(-2);
            if (e.textContent !== t) { e.textContent = t; }   // nur echte Aenderungen
        }
    }
    window.setInterval(ticke, 15000);
    // Gesperrtes Telefon friert die Seite ein: beim Zurueckkommen sofort neu rechnen.
    document.addEventListener('visibilitychange', ticke);

    // ── Aktionen ohne Neuladen
    function sende(form, knopf) {
        if (form.getAttribute('aria-busy') === 'true') { return; }   // Doppeltipp
        var daten = new URLSearchParams(new FormData(form));
        if (knopf && knopf.name) { daten.append(knopf.name, knopf.value); }
        var ziel = (knopf && knopf.getAttribute('formaction')) || form.getAttribute('action');
        form.setAttribute('aria-busy', 'true');
        fetch(ziel, {
            method: 'POST',
            body: daten,
            credentials: 'same-origin',
            headers: {'Accept': 'application/json'}
        }).then(function (r) {
            if (!r.ok || (r.headers.get('content-type') || '').indexOf('json') < 0) {
                // Abgelaufene Sitzung, widerrufener Link, CSRF: die Seite soll zeigen, was los ist.
                window.location.reload();
                return null;
            }
            return r.json();
        }).then(function (a) {
            if (!a) { return; }
            el('m-inhalt').innerHTML = a.inhalt;
            melde(a.meldung, !a.ok);
            ticke();
        }).catch(function () {
            melde('Keine Verbindung. Nochmals tippen.', true);
        }).then(function () {
            form.removeAttribute('aria-busy');
        });
    }

    function mobil(form) {
        return form && form.hasAttribute && form.hasAttribute('data-mobil') && window.fetch;
    }

    document.addEventListener('submit', function (e) {
        if (mobil(e.target)) {
            e.preventDefault();
            sende(e.target, e.submitter);
        }
    });

    // Felder (data-mobil-auto) speichern bei jeder Aenderung, ohne Knopf.
    document.addEventListener('change', function (e) {
        var form = e.target.form;
        if (mobil(form) && form.hasAttribute('data-mobil-auto')) {
            sende(form, null);
        }
    });

    // ── Rueckfrage beim Loeschen: auf- und zuklappen ohne Anfrage
    document.addEventListener('click', function (e) {
        var t = e.target.closest ? e.target.closest('[data-frage],[data-zu]') : null;
        var frage = t ? el(t.getAttribute('data-frage') || t.getAttribute('data-zu')) : null;
        if (!frage) { return; }
        e.preventDefault();
        frage.hidden = t.hasAttribute('data-zu') ? true : !frage.hidden;
    });

    // ── Langer Druck auf den Vorwaerts-Knopf: zur Uebersicht
    var uhr = null;
    var lang = false;

    function abbrechen() {
        window.clearTimeout(uhr);
        uhr = null;
    }

    document.addEventListener('pointerdown', function (e) {
        var k = e.target.closest ? e.target.closest('[data-lang-ziel]') : null;
        lang = false;
        abbrechen();
        if (!k) { return; }
        uhr = window.setTimeout(function () {
            uhr = null;
            lang = true;
            if (navigator.vibrate) { navigator.vibrate(15); }
            window.location.assign(k.getAttribute('data-lang-ziel'));
        }, SCHWELLE_MS);
    });
    ['pointerup', 'pointercancel'].forEach(function (n) {
        document.addEventListener(n, abbrechen);
    });
    // Nach einem langen Druck darf der Link nicht zusaetzlich weiterblaettern.
    document.addEventListener('click', function (e) {
        if (lang && e.target.closest && e.target.closest('[data-lang-ziel]')) {
            lang = false;
            e.preventDefault();
        }
    }, true);
}());
