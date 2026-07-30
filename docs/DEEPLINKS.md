# Deep Links

A deep link opens one specific record from outside the application — typically
from a notification e-mail. Clicking it selects the right tenant *and* the right
record instead of dropping the user on a list page.

The mechanism lives in `plaintext-root` and is shared: any module can register a
target and get the tenant switching, the login round-trip and the access checks
for free.

## URL format

```
https://<host>/deeplink?type=<target>&mandat=<tenant>&id=<record>
```

Nothing else is part of the contract. There is no token and no signature —
see [Security model](#security-model) for why.

## Registering a target

Implement `ch.plaintext.boot.deeplink.DeepLinkTarget` and expose it as a Spring
bean. That is the whole integration.

```java
@Component
public class AuszahlungDeepLinkTarget implements DeepLinkTarget {

    private final AuszahlungRepository repository;

    @Override public String getType()  { return "auszahlung"; }
    @Override public String getView()  { return "auszahlungen.html"; }
    @Override public String getLabel() { return "Auszahlung"; }

    @Override
    public boolean isAccessible(String mandat, String id) {
        return repository.findById(Long.valueOf(id))
                .filter(a -> mandat.equalsIgnoreCase(a.getMandat()))
                .isPresent();
    }
}
```

Targets are validated at startup: an invalid `type`, a missing or absolute
`view`, or a duplicate `type` fails the context. A silent gap at runtime is
worse than a failed boot.

## Building a link

Inject `DeepLinkService` — do not assemble the URL by hand.

```java
String link = deepLinkService.buildAbsoluteLink(
        "auszahlung", auszahlung.getMandat(), String.valueOf(auszahlung.getId()));
```

`buildAbsoluteLink` uses the configured `plaintext.baseurl` (background jobs have
no request to derive it from). `buildRelativeLink` returns the context-relative
path for in-app links.

## Consuming the parameter on the target page

The user lands on `<view>?<paramName>=<id>` (`id` unless the target overrides
`getParamName()`). The page reads it like any other view parameter:

```xml
<f:metadata>
    <f:viewParam name="id" value="#{auszahlungBean.deepLinkId}"/>
    <f:event type="preRenderView" listener="#{auszahlungBean.oeffneDeepLink}"/>
</f:metadata>
```

Two things the page still owns:

- **Load the record tenant-scoped.** The deep-link check is a gate in front of
  the page, not a replacement for the page's own filtering.
- **Make the record visible.** If the list is paginated, jump to the page that
  contains the record — otherwise the selection is invisible.

## Security model

A deep link carries **no authorisation**. It is an address, not a capability:
there is no secret in it, nothing is persisted, and nothing can be revoked
because there is nothing to revoke. Every hit is authorised from scratch against
the signed-in user.

`/deeplink` runs these checks in order, each of them fail-closed:

| # | Check | Rejected when |
|---|-------|---------------|
| 1 | Authentication | anonymous → login, target remembered server-side only |
| 2 | Parameter shape | anything outside `[A-Za-z0-9_-]` (see `DeepLinkFormat`) |
| 3 | Registered type | no module owns this `type` |
| 4 | Tenant access | tenant not in `PlaintextSecurity#getAllowedMandate()` |
| 5 | Record access | `DeepLinkTarget#isAccessible` returns false or throws |

Consequences worth spelling out:

- **No cross-tenant view.** The tenant is never switched — not even briefly —
  unless the user could have selected it in the topbar anyway. If check 5 then
  fails, the previous tenant is restored.
- **Guessing IDs does not help.** Check 5 is server-side and independent of what
  the menu shows.
- **The target view comes from the registry**, never from the URL. A manipulated
  link cannot point at an arbitrary page.
- **No open redirect.** When the user is not signed in, only the three validated
  identifiers are stored in the server session — never a URL. After login they
  are reassembled into a `/deeplink` call that runs the full chain again. The
  stored target is single-use.
- **Rejections are indistinguishable.** Every failure lands on
  `access-denied.html` with no reason in the URL, so the response cannot be used
  to probe which records exist. The reason is logged.

Forced password change takes precedence over a pending deep link.

## Root overview page

`deeplinks.html` (Root menu, `ROLE_ROOT`, additionally hard-wired in
`PlaintextSecurityConfig.ROOT_ONLY_PAGES`) lists the registered targets with
their type, view, parameter and URL pattern, and can generate a link for a given
tenant and ID. Since nothing is persisted, the page shows the *registry*, which
is what you need when debugging a link that does not work.
