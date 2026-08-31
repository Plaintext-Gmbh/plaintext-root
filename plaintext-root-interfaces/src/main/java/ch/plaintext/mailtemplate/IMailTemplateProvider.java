/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate;

import java.util.Map;

/**
 * Renders a mail text (subject + body) for a {@code templateKey}: the tenant's DB override if one
 * exists ({@code plaintext-admin-mailtemplate}), otherwise the default text passed in by the
 * caller. Placeholder syntax {@code {name}}, substituted uniformly in both cases.
 *
 * <p>Consumers (modules without a dependency on {@code plaintext-admin-mailtemplate}) inject this
 * as {@code @Autowired(required = false)} and fall back to the unchanged default when the bean is
 * missing (analogous to {@code I18nProvider}/{@code I18nEL}).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface IMailTemplateProvider {

    /**
     * @param mandat        tenant whose DB override is looked up (there is no cross-tenant
     *                      override concept — every tenant can store its own text)
     * @param templateKey   unique key, e.g. {@code auth.registration}
     * @param defaultBetreff default subject (placeholder syntax {@code {name}}), if no override exists
     * @param defaultBody    default body (placeholder syntax {@code {name}}), if no override exists
     * @param platzhalter    substitution values, applied to subject AND body (default or override alike)
     * @return rendered subject and body with the placeholders substituted
     */
    RenderedMail render(String mandat, String templateKey, String defaultBetreff, String defaultBody,
                        Map<String, String> platzhalter);

    record RenderedMail(String betreff, String body) {}
}
