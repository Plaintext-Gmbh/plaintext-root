/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Auf- und Zuklappen des Seitenmenues (menu.xhtml): drei Ebenen, gemessene Uebergangshoehen,
 * Wiederherstellen des zuletzt offenen Zweigs aus dem sessionStorage, Flyout-Verhalten in den
 * Modi Slim/Horizontal.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * 241 Zeilen standen als Inline-<script> am Ende von menu.xhtml — der groesste Einzelblock in
 * root, eingebunden auf jeder eingeloggten Seite. Solange ein Inline-Block existiert, braucht
 * die Content-Security-Policy script-src 'unsafe-inline', und damit laeuft auch jedes
 * eingeschleuste <script>. Muster im Bestand: plaintext-layout/js/config.js.
 */

(function() {
    var openSubmenuLabel = sessionStorage.getItem('openSubmenuLabel');
    if (openSubmenuLabel) {
        window.submenuToRestore = openSubmenuLabel;
    }
})();

function isSlimOrHorizontal() {
    var w = document.querySelector('.layout-wrapper');
    return w && (w.classList.contains('layout-slim') || w.classList.contains('layout-horizontal')) && window.innerWidth >= 992;
}

// ── Genaue Aufklapphoehe ─────────────────────────────────────
// Aufgeklappt wird ueber einen max-height-Uebergang, und der
// braucht einen festen Zielwert - 'auto' laesst sich nicht
// animieren. Der Wert im Stylesheet ist damit zugleich eine harte
// Grenze: was darueber hinausragt, schneidet 'overflow: hidden'
// weg, ohne Scrollbalken und ohne Hinweis. Jede feste Grenze wird
// irgendwann erreicht, sobald eine dritte Ebene aufklappt - deren
// Eintraege zaehlen zur Hoehe des Elterncontainers dazu.
//
// Darum wird der Zielwert aus der tatsaechlich gemessenen Hoehe
// gesetzt und nach dem Uebergang auf 'none' genommen: danach
// waechst der Container frei mit, egal wie viele Eintraege noch
// dazukommen. Platz dafuer ist da, die Sidebar scrollt ohnehin
// ('.layout-menu-container { overflow-y: auto }'). Die Werte im
// Stylesheet bleiben als Reserve, falls dieses Skript nicht laeuft.
var PT_UEBERGANG_MS = 350;   // etwas mehr als die 0.3s im Stylesheet

function ptOeffnen(ul, animiert) {
    if (!ul) return;
    ul.ptOffen = true;
    if (!animiert) {
        ul.style.maxHeight = 'none';
        return;
    }
    ul.style.maxHeight = ul.scrollHeight + 'px';
    setTimeout(function() {
        // Deckel weg, sobald die Bewegung durch ist.
        if (ul.ptOffen) {
            ul.style.maxHeight = 'none';
        }
    }, PT_UEBERGANG_MS);
}

function ptSchliessen(ul, animiert) {
    if (!ul) return;
    ul.ptOffen = false;
    if (!animiert) {
        ul.style.maxHeight = '';
        return;
    }
    // Von 'none' aus laesst sich nicht animieren: erst die
    // gemessene Hoehe setzen, Layout erzwingen, dann auf 0.
    // Gemessen wird die sichtbare Hoehe, nicht die des Inhalts -
    // sonst springt ein noch gedeckeltes Menue erst auf, bevor es
    // zugeht.
    ul.style.maxHeight = ul.offsetHeight + 'px';
    void ul.offsetHeight;
    ul.style.maxHeight = '0px';
    setTimeout(function() {
        // Zurueck ans Stylesheet, das im Ruhezustand 0 vorgibt.
        if (!ul.ptOffen) {
            ul.style.maxHeight = '';
        }
    }, PT_UEBERGANG_MS);
}

// Klappt die dritte Ebene auf, waehrend der Elterncontainer noch
// einen gemessenen Wert traegt, wuerde dessen Deckel den Zuwachs
// abschneiden - hier wird er freigegeben.
function ptElternFreigeben(ul) {
    var eltern = $(ul).parent().closest('ul')[0];
    if (eltern && $(eltern).parent().hasClass('active-menuitem')) {
        eltern.ptOffen = true;
        eltern.style.maxHeight = 'none';
    }
}

// Slim und Horizontal blenden die Untermenues ueber 'display' ein
// und aus; dort stoert jede Inline-Hoehe.
function ptInlineHoehenEntfernen() {
    $('.layout-menu ul').each(function() {
        this.style.maxHeight = '';
        this.ptOffen = false;
    });
}

function setupSubmenuHandlers() {
    var openSubmenuLabel = window.submenuToRestore || sessionStorage.getItem('openSubmenuLabel');
    var openNestedLabel = sessionStorage.getItem('openNestedSubmenuLabel');

    // Dritte Ebene zuerst verdrahten: der Handler stoppt die
    // Weitergabe, damit der Klick auf ein verschachteltes Submenue
    // nicht das Elternmenue zuklappt.
    $('.layout-menu > li > ul > li.layout-nested-submenu > a')
        .off('click.nested').on('click.nested', function(e) {
            e.preventDefault();
            e.stopImmediatePropagation();
            var nested = $(this).closest('li.layout-nested-submenu');
            var label = $(this).children('span').first().text().trim();
            var animiert = !isSlimOrHorizontal();
            nested.siblings('li.layout-nested-submenu').each(function() {
                if ($(this).hasClass('active-submenu')) {
                    ptSchliessen($(this).children('ul')[0], animiert);
                }
            }).removeClass('active-submenu');
            nested.toggleClass('active-submenu');
            if (nested.hasClass('active-submenu')) {
                if (animiert) {
                    ptElternFreigeben(nested.children('ul')[0]);
                    ptOeffnen(nested.children('ul')[0], true);
                }
                sessionStorage.setItem('openNestedSubmenuLabel', label);
            } else {
                if (animiert) {
                    ptSchliessen(nested.children('ul')[0], true);
                }
                sessionStorage.removeItem('openNestedSubmenuLabel');
            }
            return false;
        });

    // Zustand der dritten Ebene wiederherstellen. Beim Laden ohne
    // Bewegung: der Uebergang ist noch gar nicht scharf
    // ('transitions-enabled' kommt erst unten).
    if (openNestedLabel) {
        $('.layout-menu > li > ul > li.layout-nested-submenu').each(function() {
            var label = $(this).children('a').first().children('span').first().text().trim();
            if (label === openNestedLabel) {
                $(this).addClass('active-submenu');
                if (!isSlimOrHorizontal()) {
                    ptOeffnen($(this).children('ul')[0], false);
                }
            }
        });
    }

    // Blaetter: Elternmenue merken. Der Selektor fasst auch die
    // dritte Ebene, deren Label-Links sind aber oben schon
    // abgefangen (stopImmediatePropagation).
    $('.layout-menu > li > ul a').off('click.submenu-item').on('click.submenu-item', function(e) {
        var parentMenuItem = $(this).closest('.layout-menu > li');
        var parentLabel = parentMenuItem.children('a').first().text().trim();
        sessionStorage.setItem('openSubmenuLabel', parentLabel);
        e.stopPropagation();

        // Close sidebar on mobile after clicking a menu item
        if (window.innerWidth < 992) {
            var wrapper = document.querySelector('.layout-wrapper');
            if (wrapper) {
                wrapper.classList.remove('layout-mobile-active');
                document.body.classList.remove('blocked-scroll');
            }
        }
    });

    $('.layout-menu > li').each(function() {
        var item = $(this);
        var link = item.children('a').first();
        var submenu = item.children('ul').first();
        var label = link.text().trim();

        if (submenu.length > 0) {
            // Sidebar: restore state
            if (!isSlimOrHorizontal() && openSubmenuLabel && label === openSubmenuLabel) {
                item.addClass('active-menuitem');
                // Ohne Bewegung, und nach der dritten Ebene oben -
                // 'none' deckelt deren Eintraege dann nicht mit.
                ptOeffnen(submenu[0], false);
            }

            // Click handler
            link.off('click');
            link.on('click', function(e) {
                e.preventDefault();
                e.stopImmediatePropagation();

                var animiert = !isSlimOrHorizontal();
                $('.layout-menu > li').not(item).each(function() {
                    if (animiert && $(this).hasClass('active-menuitem')) {
                        ptSchliessen($(this).children('ul')[0], true);
                    }
                }).removeClass('active-menuitem');
                item.toggleClass('active-menuitem');

                if (animiert) {
                    if (item.hasClass('active-menuitem')) {
                        ptOeffnen(submenu[0], true);
                        sessionStorage.setItem('openSubmenuLabel', label);
                    } else {
                        ptSchliessen(submenu[0], true);
                        sessionStorage.removeItem('openSubmenuLabel');
                    }
                }
                return false;
            });

            // Slim/Horizontal: hover to open
            item.off('mouseenter.slim mouseleave.slim');
            item.on('mouseenter.slim', function() {
                if (isSlimOrHorizontal()) {
                    $('.layout-menu > li').not(item).removeClass('active-menuitem');
                    item.addClass('active-menuitem');
                }
            });
            item.on('mouseleave.slim', function() {
                if (isSlimOrHorizontal()) {
                    item.removeClass('active-menuitem');
                }
            });
        }
    });

    // Modus- und Breitenwechsel: im Flyout stoeren die gesetzten
    // Hoehen, zurueck in der Sidebar fehlen sie. Beides hier
    // nachziehen, sonst haengt ein Menue nach dem Wechsel an
    // einem Wert fest, der nicht mehr zu seinem Inhalt passt.
    if (isSlimOrHorizontal()) {
        ptInlineHoehenEntfernen();
    }
    $(window).off('resize.ptmenu').on('resize.ptmenu', function() {
        if (isSlimOrHorizontal()) {
            ptInlineHoehenEntfernen();
        } else {
            $('.layout-menu > li.active-menuitem > ul, .layout-menu li.layout-nested-submenu.active-submenu > ul')
                .each(function() { ptOeffnen(this, false); });
        }
    });

    setTimeout(function() {
        $('.layout-menu').addClass('transitions-enabled');
    }, 100);
}

$(function() { setupSubmenuHandlers(); });
setTimeout(setupSubmenuHandlers, 1500);
