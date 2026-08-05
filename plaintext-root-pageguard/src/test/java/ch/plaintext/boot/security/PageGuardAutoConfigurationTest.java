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
 * Sichert die eine Eigenschaft, wegen der es dieses Modul gibt: der Guard muss auch dann laufen,
 * wenn die konsumierende App {@code ch.plaintext} <b>nicht</b> component-scannt.
 *
 * <p>Der {@link ApplicationContextRunner} startet hier ausschliesslich die beiden
 * AutoConfigurations — keinen Scan, keine Stereotypen. Wuerde jemand die Bean-Definitionen wieder
 * an {@code @Service}/{@code @Component} haengen, faellt dieser Test um. Ohne ihn faellt statt
 * dessen nichts um: die App startet sauber und hat einfach keinen Seitenschutz. Genau diese Form
 * von Fehler ist teuer, weil sie erst auffaellt, wenn jemand eine URL direkt aufruft.
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
     * Das Template ruft {@code #{pageAccessGuardBackingBean.checkPageAccess()}} bei jedem
     * preRenderView — fehlt die Bean, bricht in einem Konsumenten ohne ch.plaintext-Scan jede
     * mit dem Template ausgelieferte Seite mit "Target Unreachable" ab.
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
     * Der Praefix ist beim Herausloesen aus {@code PlaintextSecurityProperties} unveraendert
     * geblieben. Waere er es nicht, wuerden bestehende {@code application.yml} still auf den
     * Default zurueckfallen — die App startet, der Guard steht aber woanders als konfiguriert.
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

    /** Eine App darf jede Bean ersetzen, ohne dass die AutoConfiguration dazwischenfunkt. */
    @Test
    void weichtEigenenBeansDerApp() {
        runner.withBean(PageAccessGuardService.class,
                        () -> new PageAccessGuardService(null, new PageGuardProperties()))
                .run(context -> assertThat(context).hasSingleBean(PageAccessGuardService.class));
    }
}
