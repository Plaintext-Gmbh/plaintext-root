/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.menu.MenuAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secures the one property this module exists for: the guard must run even when the consuming app
 * does <b>not</b> component-scan {@code ch.plaintext}.
 *
 * <p>The {@link ApplicationContextRunner} here starts nothing but the two auto-configurations — no
 * scan, no stereotypes. If someone hung the bean definitions back onto
 * {@code @Service}/{@code @Component}, this test would fall over. Without it, nothing would fall
 * over instead: the app starts cleanly and simply has no page protection. Exactly this form of bug
 * is expensive, because it only surfaces once someone calls a URL directly.
 */
class PageGuardAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MenuAutoConfiguration.class, PageGuardAutoConfiguration.class));

    @Test
    void stelltDenGuardOhneComponentScanBereit() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PageAccessGuardService.class);
            assertThat(context).hasSingleBean(PageGuardProperties.class);
            assertThat(context).hasSingleBean(MenuRegistry.class);
        });
    }

    /**
     * The template calls {@code #{pageAccessGuardBackingBean.checkPageAccess()}} on every
     * preRenderView — if the bean is missing, every page shipped with the template fails with
     * "Target Unreachable" in a consumer without the ch.plaintext scan.
     */
    @Test
    void stelltDieBackingBeanOhneComponentScanBereit() {
        runner.run(context -> assertThat(context).hasBean("pageAccessGuardBackingBean"));
    }

    @Test
    void istOhneKonfigurationImModusReport() {
        runner.run(context -> {
            PageAccessGuardService guard = context.getBean(PageAccessGuardService.class);
            assertThat(guard.getMode()).isEqualTo(PageGuardMode.REPORT);
            assertThat(guard.isEnabled()).isTrue();
        });
    }

    /**
     * The prefix stayed unchanged when it was extracted from
     * {@code PlaintextSecurityProperties}. If it had not, existing {@code application.yml} files
     * would silently fall back to the default — the app starts, but the guard is set to something
     * other than what was configured.
     */
    @Test
    void bindetDenUnveraendertenPraefix() {
        runner.withPropertyValues(
                        "plaintext.security.page-guard.mode=STRICT",
                        "plaintext.security.page-guard.enabled=false",
                        "plaintext.security.page-guard.allowlist[0]=public/**",
                        "plaintext.security.page-guard.aliases.rechnungdetail=rechnungen.html")
                .run(context -> {
                    PageGuardProperties properties = context.getBean(PageGuardProperties.class);
                    assertThat(properties.getMode()).isEqualTo(PageGuardMode.STRICT);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getAllowlist()).containsExactly("public/**");
                    assertThat(properties.getAliases()).containsEntry("rechnungdetail", "rechnungen.html");
                });
    }

    @Test
    void laesstSichDerStartupReportAbschalten() {
        runner.run(context -> assertThat(context).hasSingleBean(PageAccessGuardStartupReport.class));
        runner.withPropertyValues("plaintext.security.page-guard.startup-report=false")
                .run(context -> assertThat(context).doesNotHaveBean(PageAccessGuardStartupReport.class));
    }

    /** An app may replace every bean without the auto-configuration interfering. */
    @Test
    void weichtEigenenBeansDerApp() {
        runner.withBean(PageAccessGuardService.class,
                        () -> new PageAccessGuardService(null, new PageGuardProperties()))
                .run(context -> assertThat(context).hasSingleBean(PageAccessGuardService.class));
    }
}
