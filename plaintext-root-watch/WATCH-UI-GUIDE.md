# Watch UI Guide

**Audience: humans and AI agents writing watch pages in this repository.**
Read this before adding a page. It explains what the classes in `watch.css` do, why they are
shaped that way, and what does *not* work on a watch.

---

## 1. What a "watch page" actually is here

It is a normal JSF/Facelets page under `/watch/`, rendered inside `watch/frame.xhtml`. It is
**not** a native watchOS app.

It sits **behind the normal sign-in**, not under `/nosec/`. That was a deliberate choice: the
view is opened from a phone, which signs in once and keeps the session. A token in the URL
would buy nothing here and would leak into server logs, browser history and referrers.

**Know the limits before you design** (measured 18.09.2026, sources in card 1245):

| You may want | On an Apple Watch |
|---|---|
| Open the page | Only by tapping a link in Messages/Mail, or via a third-party watch browser. There is **no Safari** on watchOS — not even in watchOS 26. One tab, no address bar. |
| Send a notification / alarm | **Works** via Web Push — it surfaces on the paired watch. |
| Read GPS position | **Does not work.** `navigator.geolocation` returns "Access Denied" on watchOS. |
| Trigger haptics / vibration | **Does not work.** No web API for it. |

So: build for *tiny and reliable*. The same page is genuinely useful as an iPhone home-screen
web app, which is where most of the use will happen.

**Do not add geolocation or haptics to a watch page.** If you need to show that they exist,
put them on the element gallery page labelled as unavailable — do not wire them up.

---

## 2. The page contract

Contribute one Spring bean per page implementing `WatchPage`:

```java
@Component
public class ZeitWatchPage implements WatchPage {
    public String id()    { return "zeit"; }              // stable, persisted per user
    public String title() { return "Zeit"; }              // ~12 characters
    public String view()  { return "/watch/zeit.xhtml"; }
    public int order()    { return 10; }                  // home is 0
    public boolean available() { return true; }           // per-user switch goes here
}
```

`id()` is written into the database as "where the user was". **Never change it** once released;
add a new page instead.

`available()` is asked on every move, never cached — a page switched off mid-session disappears
at once instead of rendering empty.

### Loading the page's data

A page that shows data must hook into the `laden` insert. It is the **only** place where that
works:

```xml
<ui:composition xmlns:f="http://java.sun.com/jsf/core"
                template="/watch/frame.xhtml">

    <ui:define name="titel">Zeit</ui:define>

    <ui:define name="laden">
        <f:event type="preRenderView" listener="#{zeitWatchBean.seitenaufruf()}"/>
    </ui:define>
```

Do **not** write your own `<f:metadata>`: a page that uses a template contributes `ui:define`
blocks only, and JSF reads the metadata from the view root — which is `frame.xhtml`, not your
page. A second `f:metadata` is silently ignored, the listener never fires, and the page renders
perfectly well with empty lists. That is exactly how four pages shipped in card 1248; see card
1253. `WatchSeiteLaedtVertragTest` in plaintext-app now fails when a bean with `seitenaufruf()`
is not called from its page.

Remember `xmlns:f` in the `ui:composition` tag — two of those four pages did not declare it.

---

## 3. The classes, and when to use them

### Layout

| Class | Use for | Notes |
|---|---|---|
| `.w-wrap` | outermost element of every page | caps width at 420px, honours the safe area |
| `.w-card` | any grouping | the only container; do not nest cards |
| `.w-row` | exactly two fields side by side | the **only** horizontal layout; use it for from/to times |

Everything else is one column. On a 40mm screen two columns of text are unreadable.

### Showing a number

```html
<div class="w-widgets">
  <div class="w-widget"><div class="w-value">7</div><div class="w-label">Heute</div></div>
  <div class="w-widget"><div class="w-value">31</div><div class="w-label">Woche</div></div>
</div>
```

`.w-widgets` is two columns, `.w-widgets.w-3` is three; below 250px both collapse to one.
`.w-value` uses tabular figures so a counting number does not jiggle.

### Buttons

`.w-btn` is full width and at least 44px tall — Apple's minimum for a tap that reliably hits.
`.w-btn-go` (green) and `.w-btn-stop` (red) carry meaning, but **always repeat that meaning in
the label**: colour alone fails for colour-blind users and in bright sunlight.

There is no `:hover` anywhere in this stylesheet, on purpose: a watch has no pointer, and
hover styles get stuck in the "on" state after a tap.

### Inputs

Use native controls with `.w-field`. Do **not** build your own pickers: the system time picker
and `<select>` are large, familiar, and work with the Digital Crown.

`font-size` must stay at 16px or above. Below that, iOS zooms the page when the field takes
focus and the user has to pinch back out.

### Confirmation

Deleting asks first — inline, never as a modal:

```html
<div class="w-confirm">
  <div class="w-confirm-text">Eintrag löschen?</div>
  <div class="w-nav">
    <button class="w-btn w-btn-stop">Löschen</button>
    <button class="w-btn">Abbrechen</button>
  </div>
</div>
```

A modal on a 40mm screen covers the whole display and is hard to dismiss — the user cannot see
what they are deleting while deciding.

---

## 4. Rules that are easy to get wrong

1. **One screen, one job.** If a page needs scrolling on a watch, split it into two pages.
2. **No web fonts.** The system stack is already loaded; a font download over a watch's link
   costs seconds.
3. **No `title` attributes, no tooltips.** There is nothing to hover with.
4. **Labels above fields, never beside them.** Beside costs half the width.
5. **State in words.** "Läuft seit 09:12" beats a green dot nobody can interpret.
6. **Keep the DOM small.** The link-tap view is a cut-down renderer; long lists (>20 rows) may
   simply not render. Paginate or cap.
7. **Test at 200px width**, not just in a desktop browser shrunk to phone size.
8. **Never bring your own `<f:metadata>`.** Load through the `laden` insert (section 2). Your
   own metadata block is ignored without a word and the page just stays empty.
9. **A home tile reads the service, never the page bean.** Tiles are singletons; a singleton
   depending on a session-scoped bean makes Spring refuse to build the context at all.

---

## 5. PrimeFaces or plain HTML?

Open question, deliberately not decided yet (card 1247). PrimeFaces components bring a lot of
JavaScript, and the cut-down renderer on the watch may not run it. The element gallery page
carries **both variants of the same controls** so the decision can be made from a measurement
on a real screen rather than from taste.

Until that is settled: **plain HTML plus these classes** for anything that must work on the
watch itself. PrimeFaces stays fine for the regular application pages.

---

## 6. Where things live

```
plaintext-root-watch/
  src/main/java/ch/plaintext/watch/
    page/WatchPage.java           the contract above
    page/WatchPageRegistry.java   ordering, next/previous, wrap-around
    service/WatchStateService.java per-user position and switches
    entity/WatchUserState.java    what is persisted
  src/main/resources/
    META-INF/resources/watch/watch.css
    META-INF/resources/watch/    the pages themselves
```

The signed-in user always comes from `PlaintextSecurityHolder`, never from a request
parameter. An earlier module in this house took it from a parameter and thereby let anyone read
anyone else's data (card 1195).
