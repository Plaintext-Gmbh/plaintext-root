/*
 * Copyright (C) plaintext.ch, 2026.
 *
 * Claude-Summary (claudesummary.xhtml): den im versteckten #markdown-data transportierten
 * Markdown-Text per marked.js zu HTML rendern und in #markdown-content einhaengen.
 *
 * WARUM ALS EIGENE DATEI (Welle 4, CSP ohne 'unsafe-inline'):
 * Der Code stand als Inline-<script> in der Seite. Solange auch nur ein Inline-Block existiert,
 * muss die Content-Security-Policy script-src 'unsafe-inline' fuehren — und damit laeuft auch
 * jedes eingeschleuste <script>: die CSP ist dann kein XSS-Schutz mehr, sondern Dekoration.
 * Muster im Bestand: plaintext-layout/js/config.js.
 */

// Render an error message into a target node *without* string-concatenating
// user-controlled values into innerHTML (CodeQL js/xss-through-dom and
// js/xss-through-exception findings, alerts #12, #13).
function renderErrorMessage(target, text) {
    var p = document.createElement('p');
    p.style.color = 'red';
    p.textContent = text;          // textContent escapes any HTML
    target.replaceChildren(p);
}

function renderMarkdown() {
    console.log('renderMarkdown() called');

    // Configure marked.js
    if (typeof marked !== 'undefined') {
        marked.setOptions({
            breaks: true,
            gfm: true,
            headerIds: true,
            mangle: false
        });
        console.log('marked.js configured');
    } else {
        console.warn('marked.js not loaded, retrying...');
        setTimeout(renderMarkdown, 100);
        return;
    }

    // Get the markdown content from the hidden div
    var markdownDataElement = document.getElementById('markdown-data');
    var markdownContentElement = document.getElementById('markdown-content');

    console.log('markdownDataElement:', markdownDataElement);
    console.log('markdownContentElement:', markdownContentElement);

    if (markdownDataElement && markdownContentElement) {
        try {
            var markdownContent = markdownDataElement.textContent || markdownDataElement.innerText;
            markdownContent = markdownContent.trim();

            console.log('Markdown content length:', markdownContent.length);
            console.log('Markdown content preview:', markdownContent.substring(0, 100));

            if (!markdownContent) {
                console.error('No markdown content found!');
                renderErrorMessage(markdownContentElement, 'Kein Markdown-Inhalt gefunden!');
                return;
            }

            // Render markdown to HTML. The markdown comes from our own
            // backend (admin-only requirements summary), so trusted —
            // we still avoid string-concatenation of user-controlled
            // values into innerHTML (see renderErrorMessage below).
            var html = marked.parse ? marked.parse(markdownContent) : marked(markdownContent);
            console.log('Rendered HTML length:', html.length);
            markdownContentElement.innerHTML = html;
            console.log('Markdown rendered successfully');
        } catch (e) {
            console.error('Error rendering markdown:', e);
            renderErrorMessage(markdownContentElement,
                'Fehler beim Rendern des Markdown-Inhalts: ' + e.message);
        }
    } else {
        console.log('Elements not ready, retrying...');
        setTimeout(renderMarkdown, 100);
    }
}

// Render when document is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', renderMarkdown);
} else {
    renderMarkdown();
}
