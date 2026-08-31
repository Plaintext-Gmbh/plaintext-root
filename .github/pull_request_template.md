## Summary

What this pull request does, in a sentence or two.

## Changes

- Change 1
- Change 2

## Testing

How this was tested. If a test was added, name it; if none was, say why.

## Checklist

- [ ] `mvn clean install` passes locally (needs PostgreSQL on 5434, or none at all — the tests fall back to an embedded server)
- [ ] MPL 2.0 licence header on every new file
- [ ] No secrets, tokens or credentials in the diff
- [ ] New menu entries link to `.html`, never `.xhtml` (`MenuLinkInvariantTest` enforces this)
- [ ] New JSF forms carry the CSRF token: `<input type="hidden" name="_csrf" value="#{_csrf.token}"/>`
- [ ] `CHANGELOG.md` updated under `[Unreleased]` if the change is visible to a consuming application
- [ ] Documentation updated if behaviour or setup changed — see the [documentation list](../README.md#documentation)

<!--
The pipeline runs on Woodpecker (ci.plaintext.ch), not GitHub Actions. If no
status appears on your commit at all, the pipeline configuration failed to
parse — see docs/CI.md.
-->
