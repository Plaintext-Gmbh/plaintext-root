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

// Card 1360 (HB4): only sanitised HTML reaches innerHTML. DOMPurify is loaded locally before this
// file (claudesummary.xhtml). If it is missing, fail closed: show the markdown source as plain text
// instead of rendering unfiltered HTML.
function renderSanitisedHtml(target, html, fallbackText) {
    if (typeof DOMPurify === 'undefined' || typeof DOMPurify.sanitize !== 'function') {
        console.warn('DOMPurify not loaded - showing the summary as plain text');
        var pre = document.createElement('pre');
        pre.textContent = fallbackText;
        target.replaceChildren(pre);
        return;
    }
    target.innerHTML = DOMPurify.sanitize(html, {
        USE_PROFILES: { html: true },
        FORBID_TAGS: ['form', 'input', 'button', 'select', 'textarea', 'style'],
        FORBID_ATTR: ['style', 'formaction']
    });
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

            // Render markdown to HTML and sanitise it before it goes into innerHTML (card 1360,
            // HB4). The markdown is NOT trusted: every automation token of the tenant writes it
            // (POST /nosec/api/claude/summary), and marked passes raw HTML through unchanged -
            // <form>, <a href="javascript:...">, <img onerror=...> and the like.
            var html = marked.parse ? marked.parse(markdownContent) : marked(markdownContent);
            console.log('Rendered HTML length:', html.length);
            renderSanitisedHtml(markdownContentElement, html, markdownContent);
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
