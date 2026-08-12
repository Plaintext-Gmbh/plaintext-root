/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoMasterTest {

    private RepoMaster repoMaster;
    private Map<String, PlaintextRepository> repoMap;

    @BeforeEach
    void setUp() throws Exception {
        repoMaster = new RepoMaster();
        repoMap = new HashMap<>();

        // Inject the map via reflection
        java.lang.reflect.Field mapField = RepoMaster.class.getDeclaredField("map");
        mapField.setAccessible(true);
        mapField.set(repoMaster, repoMap);
    }

    // -------------------------------------------------------------------------
    // getRepo
    // -------------------------------------------------------------------------

    @Test
    void getRepo_existingType_returnsRepo() {
        PlaintextRepository mockRepo = mock(PlaintextRepository.class);
        repoMap.put("sampleentity", mockRepo);

        assertSame(mockRepo, repoMaster.getRepo("SampleEntity"));
    }

    @Test
    void getRepo_caseInsensitive() {
        PlaintextRepository mockRepo = mock(PlaintextRepository.class);
        repoMap.put("sampleentity", mockRepo);

        assertSame(mockRepo, repoMaster.getRepo("SAMPLEENTITY"));
    }

    @Test
    void getRepo_unknownType_returnsNull() {
        assertNull(repoMaster.getRepo("UnknownType"));
    }

    // -------------------------------------------------------------------------
    // Karte 687: getNextID(Object) und das statische instance-Feld gibt es nicht
    // mehr. Die zugehoerigen Faelle sind hier ersatzlos entfallen — sie haben
    // eine Methode geprueft, die nur ihr eigener (ebenfalls toter) Aufrufer
    // erreichte. Was bleibt, ist die Registry oben.
    // -------------------------------------------------------------------------

    /**
     * Karte 687: {@code getNextID} ist weg — und zwar nachweislich, nicht nur „im Code nicht mehr
     * zu sehen". Ohne diese Zusicherung koennte die Methode samt {@code -1}-Zweig wieder
     * hereinwachsen, ohne dass ein Test rot wird: ihr einziger Aufrufer war schon vorher tot, ein
     * Verhaltenstest kann sie also gar nicht erwischen.
     */
    @Test
    void getNextID_gibtEsNichtMehr() {
        assertTrue(java.util.Arrays.stream(RepoMaster.class.getDeclaredMethods())
                        .noneMatch(m -> m.getName().equals("getNextID")),
                "RepoMaster.getNextID war die Id-Vergabe des toten Generators (Karte 687) und gab "
                        + "fuer unbekannte Typen -1 zurueck. Wer sie wieder einfuehrt, braucht "
                        + "einen erreichbaren Aufrufer und eine Antwort auf den -1-Fall.");
    }

    /** Ebenso das statische {@code instance}: es hatte nur der geloeschte Generator gelesen. */
    @Test
    void statischesInstanceFeld_gibtEsNichtMehr() {
        assertTrue(java.util.Arrays.stream(RepoMaster.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().equals("instance")),
                "Ein statischer Verweis auf eine Spring-Bean ist ein Umgehungsweg an der "
                        + "Injektion vorbei; er existierte nur fuer den geloeschten Generator.");
    }

}
