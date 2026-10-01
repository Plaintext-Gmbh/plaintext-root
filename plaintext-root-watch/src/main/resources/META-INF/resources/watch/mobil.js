/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Mobil-Framework (Karte 1355), das einzige Skript der Seiten unter /watch/m/. Es nimmt jeder
 * Aktion das Neuladen ab: Formulare mit data-mobil gehen per fetch, die JSON-Antwort bringt
 * den neuen Inhalt und die Statuszeile. Es rendert nichts (das HTML kommt fertig aus MobilHtml)
 * und ist nicht noetig: ohne Skript sind es echte POST-Formulare, der Server antwortet mit 303.
 * CSP: kein Inline-Skript, kein eval; alles haengt an data-Attributen und Ereignissen auf
 * document, damit es auch nach dem Austausch des Inhalts greift. Begruendungen und Grenzen:
 * WATCH-UI-GUIDE.md, Abschnitt 7.
 */
(function () {
    'use strict';

    var SCHWELLE_MS = 550;   // wie watch.js: kuerzer wirkt wie ein verrutschter Tipper

    function el(id) {
        return document.getElementById(id);
    }

    function melde(text, fehler) {
        var m = el('m-meldung');
        if (!m) {
            return;
        }
        m.textContent = text || '';
        m.hidden = !text;
        m.classList.toggle('w-card-warn', !!fehler);
    }

    // ── Aktionen ohne Neuladen ───────────────────────────────────────────────
    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (!form.hasAttribute || !form.hasAttribute('data-mobil') || !window.fetch) {
            return;   // ohne fetch bleibt es ein gewoehnliches Formular
        }
        e.preventDefault();
        if (form.getAttribute('aria-busy') === 'true') {
            return;   // Doppeltipp: die erste Aktion laeuft noch
        }
        var knopf = e.submitter;
        var daten = new URLSearchParams(new FormData(form));
        if (knopf && knopf.name) {
            daten.append(knopf.name, knopf.value);
        }
        var ziel = (knopf && knopf.getAttribute('formaction')) || form.getAttribute('action');
        form.setAttribute('aria-busy', 'true');
        fetch(ziel, {
            method: 'POST',
            body: daten,
            credentials: 'same-origin',
            headers: {'Accept': 'application/json'}
        }).then(function (r) {
            var typ = r.headers.get('content-type') || '';
            if (!r.ok || typ.indexOf('json') < 0) {
                // Abgelaufene Sitzung (Anmeldeseite), widerrufener Link (Sperrseite), CSRF:
                // die Seite selbst soll zeigen, was los ist — also neu laden statt raten.
                window.location.reload();
                return null;
            }
            return r.json();
        }).then(function (a) {
            if (!a) {
                return;
            }
            el('m-inhalt').innerHTML = a.inhalt;
            melde(a.meldung, !a.ok);
        }).catch(function () {
            melde('Keine Verbindung. Nochmals tippen.', true);
        }).then(function () {
            form.removeAttribute('aria-busy');
        });
    });

    // ── Rueckfrage beim Loeschen: aufklappen und zuklappen ohne Anfrage ───────
    document.addEventListener('click', function (e) {
        var t = e.target.closest ? e.target.closest('[data-frage],[data-zu]') : null;
        if (!t) {
            return;
        }
        var id = t.getAttribute('data-frage') || t.getAttribute('data-zu');
        var frage = el(id);
        if (!frage) {
            return;
        }
        e.preventDefault();
        frage.hidden = t.hasAttribute('data-zu') ? true : !frage.hidden;
    });

    // ── Langer Druck auf den Vorwaerts-Knopf: zur Uebersicht ──────────────────
    var uhr = null;
    var lang = false;

    function abbrechen() {
        if (uhr !== null) {
            window.clearTimeout(uhr);
            uhr = null;
        }
    }

    document.addEventListener('pointerdown', function (e) {
        var k = e.target.closest ? e.target.closest('[data-lang-ziel]') : null;
        lang = false;
        abbrechen();
        if (!k) {
            return;
        }
        uhr = window.setTimeout(function () {
            uhr = null;
            lang = true;
            if (navigator.vibrate) {
                navigator.vibrate(15);
            }
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
