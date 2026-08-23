/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import ch.plaintext.settings.RootUserToggleEvent;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for PlaintextInitLoader - initial user creation on startup.
 */
@ExtendWith(MockitoExtension.class)
class PlaintextInitLoaderTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ISetupConfigService setupConfigService;

    /**
     * SECURITY (Karte 314, Punkt 7): der PasswordEncoder wird jetzt injiziert (zentrale Bean mit
     * Kostenfaktor 12) statt lokal instanziiert. Als @Spy, damit die Tests weiterhin gegen echtes
     * BCrypt pruefen koennen.
     */
    @org.mockito.Spy
    private BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private PlaintextInitLoader initLoader;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void createRootUserDelayed_shouldCreateRootUser_whenNotExists() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.createRootUserDelayed();

        ArgumentCaptor<MyUserEntity> captor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(captor.capture());

        MyUserEntity rootUser = captor.getValue();
        assertEquals("root@root.root", rootUser.getUsername());
        assertTrue(rootUser.getRoles().contains("root"));
        assertTrue(rootUser.getRoles().contains("admin"));
        assertTrue(rootUser.getRoles().contains("user"));
        // Karte 306: KEIN statisches "root"-Passwort mehr.
        assertFalse(encoder.matches("root", rootUser.getPassword()),
                "Root-User darf nicht mehr mit dem statischen Passwort 'root' anlegbar sein");
    }

    /**
     * Karte 306: Der Root-Bootstrap-User bekommt ein zufaelliges Einmal-Initialpasswort (nicht
     * "root") und das Flag mustChangePassword=true, das beim ersten Login den Wechsel erzwingt.
     */
    @Test
    void createRootUserDelayed_shouldUseRandomPasswordAndForceChange() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.createRootUserDelayed();

        ArgumentCaptor<MyUserEntity> captor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(captor.capture());
        MyUserEntity rootUser = captor.getValue();

        assertTrue(rootUser.isMustChangePassword(),
                "Root-User muss beim ersten Login zum Passwortwechsel gezwungen werden");
        assertFalse(encoder.matches("root", rootUser.getPassword()));
        assertNotNull(rootUser.getPassword());
        assertFalse(rootUser.getPassword().isBlank(), "Es muss ein (gehashtes) Initialpasswort gesetzt sein");
    }

    @Test
    void createRootUserDelayed_shouldNotCreateRootUser_whenAlreadyExists() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        MyUserEntity existing = new MyUserEntity();
        existing.setUsername("root@root.root");
        when(userRepository.findByUsername("root@root.root")).thenReturn(existing);

        initLoader.createRootUserDelayed();

        verify(userRepository, never()).save(any());
    }

    @Test
    void createRootUserDelayed_shouldSkip_whenRootUserDisabled() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(false);

        initLoader.createRootUserDelayed();

        verify(userRepository, never()).findByUsername(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void onRootUserToggle_shouldCreateRootUser_whenEnabled() {
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.onRootUserToggle(new RootUserToggleEvent(this, true));

        verify(userRepository).save(any(MyUserEntity.class));
    }

    @Test
    void onRootUserToggle_shouldDeleteRootUser_whenDisabled() {
        MyUserEntity existing = new MyUserEntity();
        existing.setUsername("root@root.root");
        when(userRepository.findByUsername("root@root.root")).thenReturn(existing);

        initLoader.onRootUserToggle(new RootUserToggleEvent(this, false));

        verify(userRepository).delete(existing);
    }

    /**
     * SECURITY (Forensik 23.08.2026): Das Einmal-Initialpasswort stand im Klartext im Log — und damit im
     * Container-Log und in Graylog. Der Test haengt einen Logback-Appender an den Logger und
     * verlangt, dass in KEINER Zeile ein Klartext-Passwort auftaucht: weder der bcrypt-Hash noch
     * ein Base64url-Wort, das wie das erzeugte Passwort aussieht. {@code mustChangePassword}
     * bleibt gesetzt.
     */
    @Test
    void createRootUserDelayed_darfKeinPasswortLoggen() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlaintextInitLoader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            initLoader.createRootUserDelayed();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        ArgumentCaptor<MyUserEntity> captor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(captor.capture());
        MyUserEntity rootUser = captor.getValue();
        assertTrue(rootUser.isMustChangePassword());

        String hash = rootUser.getPassword();
        assertNotNull(hash);
        for (ILoggingEvent event : appender.list) {
            String zeile = event.getFormattedMessage();
            assertFalse(zeile.contains(hash), "Der Passwort-Hash darf nicht im Log stehen: " + zeile);
            assertFalse(zeile.toUpperCase(java.util.Locale.ROOT).contains("INITIALPASSWORT"),
                    "Es darf kein Initialpasswort mehr ausgegeben werden: " + zeile);
            // Das erzeugte Passwort ist 22 Zeichen Base64url — kein solches Wort darf im Log stehen.
            assertFalse(java.util.regex.Pattern.compile("\\b[A-Za-z0-9_-]{22}\\b").matcher(zeile).find(),
                    "Verdaechtiges Base64url-Wort (moegliches Passwort) im Log: " + zeile);
        }
        assertFalse(appender.list.isEmpty(), "Die Anlage des Root-Users muss protokolliert bleiben");
    }

    @Test
    void createRootUserDelayed_shouldSetMandatToDefault() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.createRootUserDelayed();

        ArgumentCaptor<MyUserEntity> captor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(captor.capture());

        MyUserEntity rootUser = captor.getValue();
        assertEquals("default", rootUser.getMandat());
    }
}
