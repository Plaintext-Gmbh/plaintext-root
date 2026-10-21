/*
 * Karte 1548 — UI 2: das bisschen Verhalten der Muster aus layout.css.
 *
 * pt:filter: der Knopf «Filter» (nur mobil sichtbar) klappt die Filter seiner .pt-filter auf und
 * zu. Angebunden ueber das data-Attribut und EINEN Listener am document statt onclick am Knopf:
 * kein Inline-JavaScript (PlaintextInlineJsVertragTest), und der Listener gilt auch fuer Knoepfe,
 * die eine Ajax-Antwort spaeter einsetzt.
 */
(function () {
    'use strict';

    document.addEventListener('click', function (e) {
        var knopf = e.target.closest ? e.target.closest('[data-pt-filter-umschalten]') : null;
        if (!knopf) {
            return;
        }
        e.preventDefault();
        var filter = knopf.closest('.pt-filter');
        if (!filter) {
            return;
        }
        var offen = filter.classList.toggle('pt-filter-offen');
        knopf.setAttribute('aria-expanded', offen ? 'true' : 'false');
    });
})();
