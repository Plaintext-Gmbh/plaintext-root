/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Globale Suche in der Topbar (Cmd/Ctrl+K): Eingabe entprellen, /api/search abfragen, Treffer
 * gruppiert rendern, Tastaturnavigation, Mobile-Vollbreiten-Overlay.
 *
 * Die serverseitigen Werte (Kontextpfad, Platzhaltertexte) kommen als data-Attribute an
 * #global-search — im Skriptkoerper steht keine EL.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Der Block stand inline am Ende von includes/topbar.xhtml. Solange ein Inline-<script>
 * existiert, muss die Content-Security-Policy script-src 'unsafe-inline' fuehren — dann laeuft
 * auch jedes eingeschleuste <script>. Muster im Bestand: plaintext-layout/js/config.js.
 *
 * ACHTUNG BEIM VERGLEICH MIT DER ALTEN FASSUNG: der Block stand in einer CDATA-Sektion. Die
 * Ersetzungstabelle in esc() enthaelt deshalb WOERTLICH '&amp;', '&lt;' usw. — in CDATA loest
 * der XML-Parser nichts auf, und genau diese Zeichenfolgen sollen ausgegeben werden. In einer
 * .js-Datei gilt dasselbe: hier steht kein XML mehr.
 */

(function () {
    var root = document.getElementById('global-search');
    if (!root) return;
    var input = document.getElementById('global-search-input');
    var box = document.getElementById('global-search-results');
    if (!input || !box) return;

    var ctx = root.getAttribute('data-ctx') || '';
    var EMPTY = root.getAttribute('data-empty') || 'Keine Treffer';
    var HINT = root.getAttribute('data-hint') || '';
    var debounceTimer = null;
    var activeIndex = -1;
    var items = [];      // flache Liste der aktuell gerenderten Treffer-Elemente
    var lastQuery = '';

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function close() {
        box.style.display = 'none';
        box.innerHTML = '';
        items = [];
        activeIndex = -1;
        input.setAttribute('aria-expanded', 'false');
        root.classList.remove('gs-open'); // Mobile-Vollbreiten-Overlay einklappen (Desktop: no-op)
    }

    function openBox() {
        box.style.display = 'block';
        input.setAttribute('aria-expanded', 'true');
    }

    function navigate(link) {
        if (!link) return;
        var sep = link.charAt(0) === '/' ? '' : '/';
        window.location.href = ctx + sep + link;
    }

    function render(groups) {
        box.innerHTML = '';
        items = [];
        activeIndex = -1;
        if (!groups || groups.length === 0) {
            var e = document.createElement('div');
            e.className = 'global-search-empty';
            e.textContent = EMPTY;
            box.appendChild(e);
            openBox();
            return;
        }
        groups.forEach(function (g) {
            var title = document.createElement('div');
            title.className = 'global-search-group-title';
            title.textContent = g.module;
            box.appendChild(title);
            (g.hits || []).forEach(function (h) {
                var a = document.createElement('a');
                a.className = 'global-search-item';
                a.setAttribute('role', 'option');
                a.href = '#';
                var iconCls = h.icon ? esc(h.icon) : 'pi pi-angle-right';
                a.innerHTML = '<i class="gs-icon ' + iconCls + '"></i>'
                    + '<span class="gs-text">'
                    + '<span class="gs-title">' + esc(h.title) + '</span>'
                    + (h.subtitle ? '<span class="gs-sub">' + esc(h.subtitle) + '</span>' : '')
                    + '</span>';
                var link = h.link;
                a.addEventListener('click', function (ev) { ev.preventDefault(); navigate(link); });
                box.appendChild(a);
                items.push(a);
            });
        });
        openBox();
    }

    function setActive(idx) {
        if (items.length === 0) return;
        if (activeIndex >= 0 && items[activeIndex]) items[activeIndex].classList.remove('active');
        activeIndex = (idx + items.length) % items.length;
        var el = items[activeIndex];
        el.classList.add('active');
        el.scrollIntoView({ block: 'nearest' });
    }

    function doSearch(q) {
        fetch(ctx + '/api/search?q=' + encodeURIComponent(q), {
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        }).then(function (r) {
            if (!r.ok) throw new Error('search failed: ' + r.status);
            return r.json();
        }).then(function (data) {
            // Nur rendern, wenn die Antwort noch zur aktuellen Eingabe passt
            if (input.value.trim() !== q) return;
            render(data && data.groups ? data.groups : []);
        }).catch(function () { close(); });
    }

    input.addEventListener('input', function () {
        var q = input.value.trim();
        if (debounceTimer) clearTimeout(debounceTimer);
        if (q.length < 2) {
            if (q.length === 0) { close(); }
            else {
                box.innerHTML = '<div class="global-search-empty">' + esc(HINT) + '</div>';
                openBox();
            }
            lastQuery = q;
            return;
        }
        lastQuery = q;
        debounceTimer = setTimeout(function () { doSearch(q); }, 200);
    });

    input.addEventListener('keydown', function (ev) {
        if (ev.key === 'ArrowDown') { ev.preventDefault(); setActive(activeIndex + 1); }
        else if (ev.key === 'ArrowUp') { ev.preventDefault(); setActive(activeIndex - 1); }
        else if (ev.key === 'Enter') {
            if (activeIndex >= 0 && items[activeIndex]) { ev.preventDefault(); items[activeIndex].click(); }
        } else if (ev.key === 'Escape') {
            input.blur(); close();
        }
    });

    input.addEventListener('focus', function () {
        if (items.length > 0) openBox();
    });

    document.addEventListener('click', function (ev) {
        if (!root.contains(ev.target)) close();
    });

    // ── Mobile: eingeklappte Lupe antippen → Vollbreiten-Suchleiste öffnen ──
    var mq = window.matchMedia('(max-width: 640px)');
    function isMobile() { return mq.matches; }
    var boxEl = root.querySelector('.global-search-box');
    if (boxEl) {
        boxEl.addEventListener('click', function () {
            if (isMobile() && !root.classList.contains('gs-open')) {
                root.classList.add('gs-open');
                input.focus();
            }
        });
    }
    var closeBtn = root.querySelector('.global-search-close');
    if (closeBtn) {
        closeBtn.addEventListener('click', function (ev) {
            ev.preventDefault(); ev.stopPropagation();
            input.value = '';
            input.blur();
            close();
        });
    }
    // Beim Wechsel zurück auf Desktop-Breite das Overlay sicher einklappen
    if (mq.addEventListener) {
        mq.addEventListener('change', function (e) { if (!e.matches) root.classList.remove('gs-open'); });
    }

    // Globaler Cmd/Ctrl+K → Fokus auf das Suchfeld (auf Mobile zusätzlich Leiste öffnen)
    document.addEventListener('keydown', function (ev) {
        if ((ev.metaKey || ev.ctrlKey) && (ev.key === 'k' || ev.key === 'K')) {
            ev.preventDefault();
            if (isMobile()) root.classList.add('gs-open');
            input.focus();
            input.select();
        }
    });
})();
