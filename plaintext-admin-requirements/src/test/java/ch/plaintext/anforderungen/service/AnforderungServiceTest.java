/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.repository.AnforderungRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnforderungServiceTest {

    @Mock
    private AnforderungRepository repository;

    @Mock
    private PlaintextSecurity security;

    @InjectMocks
    private AnforderungService service;

    // --- getAllAnforderungen ---

    @Test
    void getAllAnforderungenReturnsList() {
        Anforderung a = new Anforderung();
        a.setTitel("Test");
        when(repository.findByMandatOrderByCreatedDateDesc("mandatA")).thenReturn(List.of(a));

        List<Anforderung> result = service.getAllAnforderungen("mandatA");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitel()).isEqualTo("Test");
    }

    @Test
    void getAllAnforderungenReturnsEmptyList() {
        when(repository.findByMandatOrderByCreatedDateDesc("mandatA")).thenReturn(List.of());

        assertThat(service.getAllAnforderungen("mandatA")).isEmpty();
    }

    // --- getAllAnforderungenForCurrentUser ---

    @Test
    void getAllAnforderungenForCurrentUserUsesSecurityMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        when(repository.findByMandatOrderByCreatedDateDesc("mandatA")).thenReturn(List.of());

        service.getAllAnforderungenForCurrentUser();

        verify(repository).findByMandatOrderByCreatedDateDesc("mandatA");
    }

    @Test
    void getAllAnforderungenForCurrentUserThrowsForNoAuth() {
        when(security.getMandat()).thenReturn("NO_AUTH");

        assertThatThrownBy(() -> service.getAllAnforderungenForCurrentUser())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid mandat");
    }

    @Test
    void getAllAnforderungenForCurrentUserThrowsForNoUser() {
        when(security.getMandat()).thenReturn("NO_USER");

        assertThatThrownBy(() -> service.getAllAnforderungenForCurrentUser())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getAllAnforderungenForCurrentUserThrowsForError() {
        when(security.getMandat()).thenReturn("ERROR");

        assertThatThrownBy(() -> service.getAllAnforderungenForCurrentUser())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getAllAnforderungenForCurrentUserThrowsForNullMandat() {
        when(security.getMandat()).thenReturn(null);

        assertThatThrownBy(() -> service.getAllAnforderungenForCurrentUser())
                .isInstanceOf(IllegalStateException.class);
    }

    // --- getByStatus ---

    @Test
    void getByStatusDelegatesToRepository() {
        when(repository.findByMandatAndStatus("mandatA", "OFFEN")).thenReturn(List.of(new Anforderung()));

        assertThat(service.getByStatus("mandatA", "OFFEN")).hasSize(1);
    }

    // --- getByPriority ---

    @Test
    void getByPriorityDelegatesToRepository() {
        when(repository.findByMandatAndPriority("mandatA", "HOCH")).thenReturn(List.of(new Anforderung()));

        assertThat(service.getByPriority("mandatA", "HOCH")).hasSize(1);
    }

    // --- getCreatedByMe ---

    @Test
    void getCreatedByMeDelegatesToRepository() {
        when(repository.findByErsteller("user1")).thenReturn(List.of(new Anforderung()));

        assertThat(service.getCreatedByMe("user1")).hasSize(1);
    }

    // --- findById ---

    @Test
    void findByIdReturnsAnforderung() {
        Anforderung a = new Anforderung();
        a.setId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(a));

        assertThat(service.findById(1L)).isPresent();
    }

    @Test
    void findByIdReturnsEmpty() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.findById(999L)).isEmpty();
    }

    // --- save ---

    @Test
    void saveNewAnforderungSetsErsteller() {
        when(security.getUser()).thenReturn("testuser");
        Anforderung anf = new Anforderung();
        anf.setStatus("OFFEN");
        when(repository.save(any(Anforderung.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(anf);

        ArgumentCaptor<Anforderung> captor = ArgumentCaptor.forClass(Anforderung.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getErsteller()).isEqualTo("testuser");
    }

    @Test
    void saveExistingAnforderungDoesNotOverrideErsteller() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setErsteller("originalUser");
        anf.setStatus("OFFEN");
        when(repository.save(any(Anforderung.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(anf);

        ArgumentCaptor<Anforderung> captor = ArgumentCaptor.forClass(Anforderung.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getErsteller()).isEqualTo("originalUser");
    }

    @Test
    void saveSetsErledigtDatumWhenStatusErledigt() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setStatus("ERLEDIGT");
        when(repository.save(any(Anforderung.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(anf);

        ArgumentCaptor<Anforderung> captor = ArgumentCaptor.forClass(Anforderung.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getErledigtDatum()).isNotNull();
    }

    @Test
    void saveDoesNotOverrideExistingErledigtDatum() {
        LocalDateTime existingDate = LocalDateTime.of(2025, 1, 1, 12, 0);
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setStatus("ERLEDIGT");
        anf.setErledigtDatum(existingDate);
        when(repository.save(any(Anforderung.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(anf);

        ArgumentCaptor<Anforderung> captor = ArgumentCaptor.forClass(Anforderung.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getErledigtDatum()).isEqualTo(existingDate);
    }

    @Test
    void saveDoesNotSetErledigtDatumForOtherStatus() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setStatus("OFFEN");
        when(repository.save(any(Anforderung.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(anf);

        ArgumentCaptor<Anforderung> captor = ArgumentCaptor.forClass(Anforderung.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getErledigtDatum()).isNull();
    }

    // --- delete ---

    @Test
    void deleteCallsRepository() {
        service.delete(1L);
        verify(repository).deleteById(1L);
    }

    // --- countByStatus ---

    @Test
    void countByStatusDelegatesToRepository() {
        when(repository.countByMandatAndStatus("mandatA", "OFFEN")).thenReturn(5L);

        assertThat(service.countByStatus("mandatA", "OFFEN")).isEqualTo(5L);
    }
}
