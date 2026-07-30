/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.service;

import ch.plaintext.mailtemplate.IMailTemplateProvider.RenderedMail;
import ch.plaintext.mailtemplate.entity.MailTemplate;
import ch.plaintext.mailtemplate.repository.MailTemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests für {@link MailTemplateService}: Cache-Fallback-Verhalten (DB-Override vor Code-Default),
 * Platzhalter-Ersetzung sowie Save/Delete-Wirkung auf den Cache.
 */
class MailTemplateServiceTest {

    private final MailTemplateRepository repo = mock(MailTemplateRepository.class);
    private final MailTemplateService service = new MailTemplateService(repo);

    private static MailTemplate template(long id, String mandat, String key, String betreff, String body) {
        MailTemplate t = new MailTemplate();
        t.setId(id);
        t.setMandat(mandat);
        t.setTemplateKey(key);
        t.setBetreff(betreff);
        t.setBody(body);
        return t;
    }

    @Test
    void render_ohneOverride_liefertDefaultMitPlatzhaltern() {
        RenderedMail r = service.render("plaintext", "auth.registration", "Hallo {name}", "Willkommen {name}!",
                Map.of("name", "Anna"));

        assertEquals("Hallo Anna", r.betreff());
        assertEquals("Willkommen Anna!", r.body());
    }

    @Test
    void render_mitOverride_liefertOverrideStattDefault() {
        when(repo.findByMandatAndTemplateKey("plaintext", "auth.registration")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> {
            MailTemplate t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        service.save("plaintext", "auth.registration", "Custom {name}", "Custom Body {name}", false);

        RenderedMail r = service.render("plaintext", "auth.registration", "Default Betreff", "Default Body",
                Map.of("name", "Anna"));

        assertEquals("Custom Anna", r.betreff());
        assertEquals("Custom Body Anna", r.body());
    }

    @Test
    void render_overrideAndererMandant_wirktNichtAufDenAnderen() {
        when(repo.findByMandatAndTemplateKey("mandantA", "auth.registration")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.save("mandantA", "auth.registration", "Custom A", "Body A", false);

        RenderedMail r = service.render("mandantB", "auth.registration", "Default Betreff", "Default Body", Map.of());

        assertEquals("Default Betreff", r.betreff());
        assertEquals("Default Body", r.body());
    }

    @Test
    void save_aktualisiertBestehendenOverrideStattNeuAnzulegen() {
        MailTemplate existing = template(5L, "plaintext", "auth.registration", "Alt", "Alter Body");
        when(repo.findByMandatAndTemplateKey("plaintext", "auth.registration")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MailTemplate saved = service.save("plaintext", "auth.registration", "Neu", "Neuer Body", true);

        assertEquals(5L, saved.getId());
        assertEquals("Neu", saved.getBetreff());
        assertTrue(saved.isHtml());
    }

    @Test
    void deleteOverride_faelltZurueckAufDefault() {
        MailTemplate existing = template(9L, "plaintext", "auth.registration", "Custom", "Custom Body");
        when(repo.findByMandatAndTemplateKey("plaintext", "auth.registration")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> {
            MailTemplate t = inv.getArgument(0);
            t.setId(9L);
            return t;
        });
        service.save("plaintext", "auth.registration", "Custom", "Custom Body", false);
        when(repo.findById(9L)).thenReturn(Optional.of(existing));

        service.deleteOverride(9L);
        RenderedMail r = service.render("plaintext", "auth.registration", "Default Betreff", "Default Body", Map.of());

        assertEquals("Default Betreff", r.betreff());
        assertEquals("Default Body", r.body());
    }

    @Test
    void init_laedtBestehendeOverridesAusDerRepository() {
        MailTemplate existing = template(3L, "plaintext", "auth.registration", "Aus DB", "Aus DB Body");
        when(repo.findAll()).thenReturn(List.of(existing));

        service.init();
        RenderedMail r = service.render("plaintext", "auth.registration", "Default Betreff", "Default Body", Map.of());

        assertEquals("Aus DB", r.betreff());
    }

    @Test
    void getOverrides_delegiertAnRepository() {
        List<MailTemplate> list = List.of(template(1L, "plaintext", "a", "A", "Ab"));
        when(repo.findByMandatOrderByTemplateKeyAsc("plaintext")).thenReturn(list);

        assertEquals(list, service.getOverrides("plaintext"));
    }
}
