/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Zwei globale PrimeFaces-Korrekturen, die auf JEDER Seite JEDER Anwendung gebraucht werden:
 * Auto-Close des Datum-only-p:datePicker und das Loesen des Fokus, bevor ein Dialog
 * aria-hidden bekommt.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Der Code stand als Inline-<script> im <h:head> von includes/template.xhtml. Solange auch nur
 * ein Inline-Block existiert, muss die Content-Security-Policy script-src 'unsafe-inline'
 * fuehren — und damit laeuft auch jedes eingeschleuste <script>. Die CSP ist dann kein
 * XSS-Schutz, sondern Dekoration. Muster im Bestand: plaintext-layout/js/config.js.
 */

// Globaler Fix (alle Apps): Datum-only p:datePicker schliesst nach Auswahl automatisch.
// PrimeFaces 15 schliesst den Popup-Picker nur, wenn focusOnSelect=true ist — Default ist
// false (datepicker.js), daher blieb der Chooser offen. Die eingebaute Auto-Close-Bedingung
// schliesst Zeit-Picker (showTime), Range/Multiple- und Inline-Picker bereits selbst aus;
// focusOnSelect=true ist damit fuer ALLE Picker sicher und laesst nur Datum-only-Single-
// Popups zusaetzlich automatisch schliessen. Zentral, damit jede App per root-Release profitiert.
(function() {
    function patchDatePicker(DP) {
        if (!DP || !DP.prototype || DP.prototype.__ptAutoClose) return;
        DP.prototype.__ptAutoClose = true;
        var origInit = DP.prototype.init;
        DP.prototype.init = function(cfg) {
            if (cfg) { cfg.focusOnSelect = true; }
            origInit.call(this, cfg);
        };
    }
    function tryPatch() {
        if (window.PrimeFaces && PrimeFaces.widget && PrimeFaces.widget.DatePicker) {
            patchDatePicker(PrimeFaces.widget.DatePicker);
            return true;
        }
        return false;
    }
    try {
        if (window.PrimeFaces && PrimeFaces.widget && !tryPatch()) {
            // Widget noch nicht geladen: beim spaeteren Definieren patchen (per Setter).
            var _dp;
            Object.defineProperty(PrimeFaces.widget, 'DatePicker', {
                configurable: true, enumerable: true,
                get: function() { return _dp; },
                set: function(v) { _dp = v; patchDatePicker(v); }
            });
        }
    } catch (e) { /* defensiv: Fallback ueber ready/pfAjaxComplete unten */ }
    if (window.$) {
        $(document).ready(tryPatch);
        $(document).on('pfAjaxComplete', tryPatch);
    }
})();

// Fix PrimeFaces dialog accessibility issue: remove focus before hiding.
//
// Der Guard auf window.$ ist beim Auslagern dazugekommen: der Block stand vorher als
// Inline-<script> im <h:head> und rief $(document).ready() ungeprueft auf. Fehlt jQuery zu
// diesem Zeitpunkt, wirft die Zeile — und ein Wurf auf oberster Ebene beendet den Lauf der
// ganzen Datei (belegt fuer config.js in Karte 938). Der erste Block oben prueft window.$
// bereits; hier fehlte die Pruefung nur.
if (window.$) {
$(document).ready(function() {
    var observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(mutation) {
            if (mutation.type === 'attributes' && mutation.attributeName === 'aria-hidden') {
                var dialog = $(mutation.target);
                if (dialog.attr('aria-hidden') === 'true') {
                    var focusedElement = dialog.find(':focus');
                    if (focusedElement.length > 0) {
                        focusedElement.blur();
                    }
                }
            }
        });
    });

    function observeDialogs() {
        $('div[role="dialog"]').each(function() {
            observer.observe(this, {
                attributes: true,
                attributeFilter: ['aria-hidden']
            });
        });
    }

    observeDialogs();

    $(document).on('pfAjaxComplete', function() {
        observeDialogs();
    });
});
}

// Karte 1421 (Daniel, 04.10.2026): Die Woche beginnt in ALLEN Kalendern ALLER Apps am Montag,
// unabhaengig von der Sprache. PrimeFaces 15 holt den ersten Wochentag ueber
// PrimeFaces.getLocaleSettings(userLocale) (datepicker.js, configureLocale); locale-de.js hat
// firstDayOfWeek 1, locale-en.js aber 0 = Sonntag. Wer die App auf Englisch nutzt, sah den
// Sonntag zuerst. Statt jede Locale einzeln zu kennen, setzt dieser Umschlag den Wert fuer jede
// zurueckgegebene Einstellung. Er laeuft vor den Widgets (diese Datei steht im <h:head>).
(function() {
    try {
        if (window.PrimeFaces && typeof PrimeFaces.getLocaleSettings === 'function' && !PrimeFaces.__ptMontag) {
            PrimeFaces.__ptMontag = true;
            var original = PrimeFaces.getLocaleSettings;
            PrimeFaces.getLocaleSettings = function() {
                var einstellungen = original.apply(this, arguments);
                if (!einstellungen) {
                    return einstellungen;
                }
                // Kopie statt Aenderung: die Locale-Daten von PrimeFaces bleiben unberuehrt.
                var kopie = {};
                for (var schluessel in einstellungen) {
                    kopie[schluessel] = einstellungen[schluessel];
                }
                kopie.firstDayOfWeek = 1;
                return kopie;
            };
        }
    } catch (e) {
        // Ohne PrimeFaces (Fehlerseite) gibt es nichts zu korrigieren.
    }
})();
