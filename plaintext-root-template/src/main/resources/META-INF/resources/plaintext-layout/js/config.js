/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Verhalten des Konfigurationspanels (includes/config.xhtml): Farbwahl, Dunkelmodus, Menuemodus,
 * Eingabestil und das verzoegerte Speichern der Einstellungen.
 *
 * WARUM ALS EIGENE DATEI UND NICHT INLINE (Karte 502, Ursache aus Karte 430):
 * Dieser Code stand bis 03.08.2026 als <script> mit CDATA-Block INNERHALB von
 * <h:form id="config-form">. Jede PrimeFaces-Antwort verpackt das aktualisierte Formular in
 * <update id="..."><![CDATA[ … ]]></update>. Das woertliche CDATA-Ende am Ende des Inline-Scripts
 * beendet diese aeussere Sektion vorzeitig — der XML-Parser des Browsers bricht ab und
 * PrimeFaces verwirft die KOMPLETTE Antwort. Gemeldet wird das nur ueber das jQuery-Ereignis
 * pfAjaxError: kein Serverfehler, kein Logeintrag, keine Konsolenmeldung. Sichtbar ist allein,
 * dass nichts passiert.
 *
 * config.xhtml steckt ueber das root-Template in JEDER Seite JEDER Anwendung — die Fundstelle
 * mit der groessten Reichweite. Der Nachweis des Mechanismus steht in Karte 430 (drei Laeufe
 * gegen PROD: unveraendert kaputt / CDATA-Ende maskiert heil / Kontrolle kaputt).
 *
 * Die fuenf serverseitigen Werte und die Farbpalette kommen jetzt als data-Attribute an
 * #layout-config — dieses div liegt AUSSERHALB des Formulars und wird von keinem Ajax-Update
 * angefasst. Muster im Bestand: plaintext-layout/js/layout.js.
 */
var cfgWurzel = document.getElementById('layout-config') || {dataset: {}};

var currentComponentTheme = cfgWurzel.dataset.componentTheme || '';
var currentDarkMode = cfgWurzel.dataset.darkMode || '';
var currentMenuMode = cfgWurzel.dataset.menuMode || '';
var currentInputStyle = cfgWurzel.dataset.inputStyle || '';
var currentCustomColor = cfgWurzel.dataset.customColor || '';

var pendingPreferences = {};
var hasUnsavedChanges = false;
var isInitializing = true;
var autoSaveTimer = null;
var AUTO_SAVE_DELAY = 2000;

// Color palette data (server-rendered)
var themeColors = JSON.parse(cfgWurzel.dataset.themeColors || '{}');

// Initialize
(function() {
    setTimeout(function() {
        try {
            Plaintext.Configurator.changeSectionTheme(currentDarkMode, 'layout-topbar');
            Plaintext.Configurator.changeSectionTheme(currentDarkMode, 'layout-menu');
            Plaintext.Configurator.updateInputStyle(currentInputStyle);
        } catch (e) { }
        setTimeout(function() {
            pendingPreferences = {};
            hasUnsavedChanges = false;
            isInitializing = false;
        }, 500);
    }, 300);
})();

// ===== Apply CSS variables for a color =====
function applyColorVariables(colorName, mode) {
    if (colorName === 'custom' && currentCustomColor) {
        applyCustomColorVariables(currentCustomColor, mode);
        return;
    }
    var colors = themeColors[colorName];
    if (!colors) return;
    var c = colors[mode] || colors['light'];
    var root = document.documentElement.style;
    root.setProperty('--pt-primary-color', c.primary, 'important');
    root.setProperty('--pt-primary-color-text', c.primaryText, 'important');
    root.setProperty('--pt-primary-lighter', c.primaryLighter, 'important');
    root.setProperty('--pt-primary-bg-16', c.primaryBg16, 'important');
    root.setProperty('--pt-primary-bg-04', c.primaryBg04, 'important');
    root.setProperty('--pt-focus-ring-color', c.focusRing, 'important');
}

// ===== Generate palette from hex color in JavaScript =====
function generatePaletteFromHex(hex, mode) {
    var r = parseInt(hex.substring(1, 3), 16);
    var g = parseInt(hex.substring(3, 5), 16);
    var b = parseInt(hex.substring(5, 7), 16);

    // Luminance for text color decision
    var luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    var primaryText = luminance > 0.5 ? '#212529' : '#ffffff';

    if (mode === 'dark') {
        // Lighten by 25% for dark mode primary
        var rd = Math.min(255, r + Math.round((255 - r) * 0.25));
        var gd = Math.min(255, g + Math.round((255 - g) * 0.25));
        var bd = Math.min(255, b + Math.round((255 - b) * 0.25));
        return {
            primary: 'rgb(' + rd + ',' + gd + ',' + bd + ')',
            primaryText: primaryText,
            primaryLighter: 'rgb(' + Math.round(r * 0.2) + ',' + Math.round(g * 0.2) + ',' + Math.round(b * 0.2) + ')',
            primaryBg16: 'rgba(' + rd + ',' + gd + ',' + bd + ',.16)',
            primaryBg04: 'rgba(' + rd + ',' + gd + ',' + bd + ',.04)',
            focusRing: 'rgba(' + rd + ',' + gd + ',' + bd + ',.5)'
        };
    } else {
        // Light mode
        var factor = 0.85;
        var rl = Math.min(255, Math.round(r * factor + 255 * (1 - factor)));
        var gl = Math.min(255, Math.round(g * factor + 255 * (1 - factor)));
        var bl = Math.min(255, Math.round(b * factor + 255 * (1 - factor)));
        return {
            primary: hex,
            primaryText: primaryText,
            primaryLighter: 'rgb(' + rl + ',' + gl + ',' + bl + ')',
            primaryBg16: 'rgba(' + r + ',' + g + ',' + b + ',.16)',
            primaryBg04: 'rgba(' + r + ',' + g + ',' + b + ',.04)',
            focusRing: 'rgba(' + r + ',' + g + ',' + b + ',.5)'
        };
    }
}

function applyCustomColorVariables(hex, mode) {
    var c = generatePaletteFromHex(hex, mode);
    var root = document.documentElement.style;
    root.setProperty('--pt-primary-color', c.primary, 'important');
    root.setProperty('--pt-primary-color-text', c.primaryText, 'important');
    root.setProperty('--pt-primary-lighter', c.primaryLighter, 'important');
    root.setProperty('--pt-primary-bg-16', c.primaryBg16, 'important');
    root.setProperty('--pt-primary-bg-04', c.primaryBg04, 'important');
    root.setProperty('--pt-focus-ring-color', c.focusRing, 'important');
}

// ===== Update grid selection highlight =====
function updateGridSelection(activeFile, activeHex) {
    var items = document.querySelectorAll('#themeColorGrid .theme-color-item');
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        var file = item.getAttribute('data-theme-file');
        var hex = item.getAttribute('data-custom-hex');
        var isSelected = false;
        if (activeFile === 'custom' && activeHex && hex === activeHex) {
            isSelected = true;
        } else if (activeFile !== 'custom' && file === activeFile && !hex) {
            isSelected = true;
        }
        if (isSelected) {
            item.classList.add('theme-color-selected');
            item.style.borderColor = 'var(--pt-primary-color)';
        } else {
            item.classList.remove('theme-color-selected');
            item.style.borderColor = 'var(--pt-surface-border)';
        }
    }
}

// ===== Change handlers =====
function changeThemeColor(themeFile) {
    applyColorVariables(themeFile, currentDarkMode);
    currentComponentTheme = themeFile;
    currentCustomColor = '';
    pendingPreferences['componentTheme'] = themeFile;
    pendingPreferences['customColor'] = '';
    updateGridSelection(themeFile, null);
    hasUnsavedChanges = true;
    scheduleAutoSave();
}

function selectCustomNamedColor(hex) {
    applyCustomColorVariables(hex, currentDarkMode);
    currentComponentTheme = 'custom';
    currentCustomColor = hex;
    pendingPreferences['componentTheme'] = 'custom';
    pendingPreferences['customColor'] = hex;
    updateGridSelection('custom', hex);
    hasUnsavedChanges = true;
    scheduleAutoSave();
}

// ===== Add/delete color palette functions =====
function showAddColorForm() {
    document.getElementById('addColorForm').style.display = 'block';
    var nameInput = document.getElementById('newColorName');
    if (nameInput) { nameInput.value = ''; nameInput.focus(); }
}

function hideAddColorForm() {
    document.getElementById('addColorForm').style.display = 'none';
}

function confirmAddColor() {
    var picker = document.getElementById('newColorPicker');
    var nameInput = document.getElementById('newColorName');
    var hex = picker ? picker.value : '#4CAF50';
    var name = nameInput ? nameInput.value.trim() : '';
    if (!name) { nameInput.focus(); return; }

    var params = new URLSearchParams();
    params.append('name', name);
    params.append('hex', hex);
    var csrfInput = document.querySelector('#config-form input[name="_csrf"]');
    if (csrfInput) params.append('_csrf', csrfInput.value);

    fetch('/api/preferences/add-color', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString(),
        credentials: 'same-origin'
    }).then(function(r) {
        if (r.ok) {
            // Add the new color to the grid dynamically
            addColorToGrid(name, hex);
            hideAddColorForm();
        } else { console.error('Add color failed:', r.status); }
    }).catch(function(e) { console.error('Add color error:', e); });
}

function addColorToGrid(name, hex) {
    var grid = document.getElementById('themeColorGrid');
    var addBtn = document.getElementById('addColorBtn');
    var a = document.createElement('a');
    a.href = '#';
    a.className = 'theme-color-item';
    a.setAttribute('data-theme-file', 'custom');
    a.setAttribute('data-custom-hex', hex);
    a.setAttribute('data-color-type', 'custom');
    a.setAttribute('data-color-name', name);
    a.style.cssText = 'display:flex; align-items:center; gap:8px; padding:6px 10px; border-radius:8px; border:2px solid var(--pt-surface-border); background:var(--pt-surface-50); text-decoration:none; color:var(--pt-text-color); cursor:pointer; transition:border-color 0.2s; position:relative;';
    a.onclick = function() { selectCustomNamedColor(hex); return false; };

    var circle = document.createElement('span');
    circle.style.cssText = 'width:22px; height:22px; min-width:22px; border-radius:50%; background:' + hex + '; border:1px solid rgba(0,0,0,0.1);';

    var label = document.createElement('span');
    label.style.cssText = 'font-size:0.85rem; flex:1;';
    label.textContent = name;

    var del = document.createElement('span');
    del.className = 'color-delete-btn';
    del.style.cssText = 'font-size:0.7rem; color:var(--pt-text-color-secondary); cursor:pointer; padding:2px 4px; opacity:0.5; transition:opacity 0.2s;';
    del.innerHTML = '&#x2715;';
    del.title = 'Farbe loeschen';
    del.onmouseover = function() { this.style.opacity = '1'; };
    del.onmouseout = function() { this.style.opacity = '0.5'; };
    del.onclick = function(e) { e.preventDefault(); e.stopPropagation(); deleteColor(name); };

    a.appendChild(circle);
    a.appendChild(label);
    a.appendChild(del);
    grid.insertBefore(a, addBtn);
}

function deleteColor(colorKey) {
    var params = new URLSearchParams();
    params.append('colorKey', colorKey);
    var csrfInput = document.querySelector('#config-form input[name="_csrf"]');
    if (csrfInput) params.append('_csrf', csrfInput.value);

    fetch('/api/preferences/delete-color', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString(),
        credentials: 'same-origin'
    }).then(function(r) {
        if (r.ok) {
            removeColorFromGrid(colorKey);
            // Show restore link
            var restoreLink = document.getElementById('restoreColorsLink');
            if (restoreLink) restoreLink.style.display = 'block';
        } else { console.error('Delete color failed:', r.status); }
    }).catch(function(e) { console.error('Delete color error:', e); });
}

function removeColorFromGrid(colorKey) {
    var items = document.querySelectorAll('#themeColorGrid .theme-color-item');
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        var type = item.getAttribute('data-color-type');
        if (type === 'custom' && item.getAttribute('data-color-name') === colorKey) {
            item.remove();
            return;
        }
        if (type === 'predefined' && item.getAttribute('data-theme-file') === colorKey) {
            item.remove();
            return;
        }
    }
}

function restoreAllColors() {
    var csrfInput = document.querySelector('#config-form input[name="_csrf"]');
    var params = new URLSearchParams();
    if (csrfInput) params.append('_csrf', csrfInput.value);

    fetch('/api/preferences/restore-colors', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString(),
        credentials: 'same-origin'
    }).then(function(r) {
        if (r.ok) {
            // Reload page to show all predefined colors again
            window.location.reload();
        } else { console.error('Restore colors failed:', r.status); }
    }).catch(function(e) { console.error('Restore colors error:', e); });
}

function changeDarkMode(darkMode) {
    // Update data-theme attribute (triggers CSS variable switch for surfaces)
    document.documentElement.setAttribute('data-theme', darkMode);
    // Update primary color for the new mode
    applyColorVariables(currentComponentTheme, darkMode);
    // Update layout sections
    Plaintext.Configurator.changeSectionTheme(darkMode, 'layout-menu');
    Plaintext.Configurator.changeSectionTheme(darkMode, 'layout-topbar');
    // Update branding logo theme
    var logo = document.getElementById('branding-logo');
    if (logo) { logo.setAttribute('src', logo.getAttribute('src').replace(/theme=(light|dark)/, 'theme=' + darkMode)); }
    // Update layout CSS
    var layoutLink = document.querySelector('link[href*="layout-"]');
    if (layoutLink) {
        var href = layoutLink.getAttribute('href');
        var idx = href.indexOf('layout-') + 6;
        var end = href.indexOf('.css');
        layoutLink.setAttribute('href', href.replace(href.substring(idx, end), '-' + darkMode));
    }

    currentDarkMode = darkMode;
    pendingPreferences['darkMode'] = darkMode;
    pendingPreferences['topbarTheme'] = darkMode;
    pendingPreferences['menuTheme'] = darkMode;
    hasUnsavedChanges = true;
    scheduleAutoSave();
}

function changeMenuMode(menuMode) {
    var wrapper = document.querySelector('.layout-wrapper');
    if (wrapper) {
        wrapper.classList.remove('layout-sidebar', 'layout-slim', 'layout-horizontal', 'layout-static');
        wrapper.classList.add(menuMode);
        if (menuMode === 'layout-sidebar') {
            wrapper.classList.add('layout-static');
            pendingPreferences['menuStatic'] = 'true';
        } else {
            pendingPreferences['menuStatic'] = 'false';
        }
    }
    Plaintext.Configurator.clearLayoutState();
    currentMenuMode = menuMode;
    pendingPreferences['menuMode'] = menuMode;
    hasUnsavedChanges = true;
    scheduleAutoSave();
}

function changeInputStyle(inputStyle) {
    Plaintext.Configurator.updateInputStyle(inputStyle);
    currentInputStyle = inputStyle;
    pendingPreferences['inputStyle'] = inputStyle;
    hasUnsavedChanges = true;
    scheduleAutoSave();
}

// ===== Generic change handler for radio buttons =====
function handleChange(key, value, visualChangeFunc) {
    if (isInitializing) return;
    if (visualChangeFunc) visualChangeFunc(value);
}

// ===== Auto-save =====
function scheduleAutoSave() {
    if (autoSaveTimer) clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(function() {
        if (hasUnsavedChanges) saveAllPendingChanges();
    }, AUTO_SAVE_DELAY);
}

window.addEventListener('beforeunload', function() {
    if (hasUnsavedChanges) saveAllPendingChanges();
});

document.addEventListener('click', function(e) {
    var configPanel = document.getElementById('layout-config');
    if (configPanel && !configPanel.contains(e.target) && hasUnsavedChanges) {
        saveAllPendingChanges();
    }
});

function saveAllPendingChanges() {
    if (!hasUnsavedChanges) return;
    try {
        var params = new URLSearchParams();
        for (var key in pendingPreferences) {
            params.append(key, pendingPreferences[key]);
        }
        var csrfInput = document.querySelector('#config-form input[name="_csrf"]');
        if (csrfInput) params.append('_csrf', csrfInput.value);

        fetch('/api/preferences/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params.toString(),
            credentials: 'same-origin',
            keepalive: true
        }).then(function(r) {
            if (r.ok) { hasUnsavedChanges = false; pendingPreferences = {}; }
            else { console.error('Save failed with status:', r.status); }
        }).catch(function(e) { console.error('Save error:', e); });
    } catch (e) { console.error('Save exception:', e); }
}
