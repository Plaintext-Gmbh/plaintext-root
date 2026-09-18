/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import org.springframework.stereotype.Component;

/**
 * The landing page of the watch view: a few widgets and the way into the other pages.
 *
 * <p>Always available and always first ({@code order 0}) — it is the fallback whenever a
 * remembered page has disappeared.</p>
 */
@Component
public class WatchHomePage implements WatchPage {

    @Override
    public String id() {
        return "home";
    }

    @Override
    public String title() {
        return "Übersicht";
    }

    @Override
    public String view() {
        return "/watch/home.xhtml";
    }

    @Override
    public int order() {
        return 0;
    }
}
