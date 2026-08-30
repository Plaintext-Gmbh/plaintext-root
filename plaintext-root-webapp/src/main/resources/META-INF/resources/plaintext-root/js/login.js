/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Anmeldeseite (login.xhtml): Hell-/Dunkelmodus und Akzentfarbe aus den Cookies, Aufraeumen
 * des ?logout=true-Parameters, der Modus-Umschalter und die OIDC-Weiterleitung.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Der Code stand als Inline-<script> in der Seite. Solange auch nur ein Inline-Block existiert,
 * muss die Content-Security-Policy script-src 'unsafe-inline' fuehren — und damit laeuft auch
 * jedes eingeschleuste <script>: die CSP ist dann kein XSS-Schutz mehr, sondern Dekoration.
 * Muster im Bestand: plaintext-layout/js/config.js.
 */

// Theme Management
function initTheme() {
    const savedTheme = getCookie('plaintext-theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const theme = savedTheme || (prefersDark ? 'dark' : 'light');
    applyTheme(theme);
}

function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme') || 'light';
    const newTheme = currentTheme === 'light' ? 'dark' : 'light';
    applyTheme(newTheme);
    setCookie('plaintext-theme', newTheme, 365);
}

function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    const icon = document.getElementById('theme-icon');
    if (icon) {
        icon.className = theme === 'dark' ? 'pi pi-sun' : 'pi pi-moon';
    }
}

function setCookie(name, value, days) {
    const expires = new Date();
    expires.setTime(expires.getTime() + (days * 24 * 60 * 60 * 1000));
    document.cookie = name + '=' + value + ';expires=' + expires.toUTCString() + ';path=/';
}

function getCookie(name) {
    const nameEQ = name + '=';
    const ca = document.cookie.split(';');
    for (let i = 0; i < ca.length; i++) {
        let c = ca[i];
        while (c.charAt(0) === ' ') c = c.substring(1, c.length);
        if (c.indexOf(nameEQ) === 0) return c.substring(nameEQ.length, c.length);
    }
    return null;
}

// Clean up URL parameters (remove ?logout=true)
function cleanUrl() {
    if (window.history.replaceState && window.location.search.indexOf('logout') !== -1) {
        window.history.replaceState({}, document.title, window.location.pathname);
    }
}

// Apply saved color from cookie to login page
function initColor() {
    var savedColor = getCookie('plaintext-color');
    if (savedColor) {
        // Color palette (matching ThemeColorProvider)
        var palettes = {
            'blue': '#2196F3', 'green': '#4CAF50', 'orange': '#FF9800', 'turquoise': '#00BCD4',
            'avocado': '#AEC523', 'purple': '#7B1FA2', 'red': '#F44336', 'yellow': '#FFC107',
            'lime': '#8BC34A', 'crimson': '#B71C1C'
        };
        var hex = palettes[savedColor];
        if (savedColor === 'custom') {
            hex = getCookie('plaintext-custom-color');
        }
        if (hex) {
            document.documentElement.style.setProperty('--primary-color', hex);
            // Derive darker shade for dark mode
            document.documentElement.style.setProperty('--primary-dark', hex);
        }
    }
}

// Initialize theme and color on page load
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() { initTheme(); initColor(); cleanUrl(); });
} else {
    initTheme();
    initColor();
    cleanUrl();
}

// ===== Verdrahtung (Welle 4: CSP ohne 'unsafe-inline') =====
//
// Der Modus-Umschalter trug bis 30.08.2026 onclick="toggleTheme()" am <button>, und die
// OIDC-Weiterleitung stand als zweiter Inline-<script>-Block im Seitenkoerper — mit der Ziel-URL
// direkt als EL im Skriptkoerper. Beides zwingt die Content-Security-Policy zu
// script-src 'unsafe-inline'. Die Ziel-URL kommt jetzt als data-Attribut an ein leeres,
// bedingt gerendertes <div>; im Skript steht keine EL mehr.
document.addEventListener('DOMContentLoaded', function () {
    var umschalter = document.getElementById('theme-toggle-btn');
    if (umschalter) {
        umschalter.addEventListener('click', toggleTheme);
    }

    var ziel = document.getElementById('oidc-auto-redirect');
    var url = ziel ? ziel.getAttribute('data-pt-redirect') : null;
    if (url) {
        window.location.href = url;
    }
});
