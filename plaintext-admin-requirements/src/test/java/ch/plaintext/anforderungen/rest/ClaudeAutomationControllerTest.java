/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.rest;

import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.entity.ClaudePrompt;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.HowtoRepository;
import ch.plaintext.anforderungen.service.ClaudeAutomationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaudeAutomationControllerTest {

    @Mock
    private ClaudeAutomationService service;

    @Mock
    private HowtoRepository howtoRepository;

    @InjectMocks
    private ClaudeAutomationController controller;

    // --- acknowledgePrompt ---

    @Test
    void acknowledgePromptReturns200OnSuccess() {
        when(service.acknowledgePrompt("00001", "token")).thenReturn(true);

        ResponseEntity<ClaudeAutomationController.AckResponse> response =
                controller.acknowledgePrompt("00001", "token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getPromptNumber()).isEqualTo("00001");
    }

    @Test
    void acknowledgePromptReturns401OnFailure() {
        when(service.acknowledgePrompt("00001", "bad")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.AckResponse> response =
                controller.acknowledgePrompt("00001", "bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    // --- getPromptStatus ---

    @Test
    void getPromptStatusReturns401ForInvalidToken() {
        when(service.validateToken("bad")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.StatusResponse> response =
                controller.getPromptStatus("00001", "bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getPromptStatusReturns200WithDetails() {
        when(service.validateToken("token")).thenReturn(true);
        when(service.getPromptStatus("00001")).thenReturn("ACKNOWLEDGED");
        when(service.isPromptAcknowledged("00001")).thenReturn(true);
        when(service.isPromptTimedOut("00001")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.StatusResponse> response =
                controller.getPromptStatus("00001", "token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(response.getBody().isAcknowledged()).isTrue();
        assertThat(response.getBody().isTimedOut()).isFalse();
    }

    // --- hasWork ---

    @Test
    void hasWorkReturns401ForInvalidToken() {
        when(service.validateToken("bad")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.WorkResponse> response =
                controller.hasWork("bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void hasWorkReturns200WithResult() {
        when(service.validateToken("token")).thenReturn(true);
        when(service.hasWork("token")).thenReturn(true);

        ResponseEntity<ClaudeAutomationController.WorkResponse> response =
                controller.hasWork("token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isHasWork()).isTrue();
    }

    // --- getNextTask ---

    @Test
    void getNextTaskReturns401ForInvalidToken() {
        when(service.validateToken("bad")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.TaskResponse> response =
                controller.getNextTask("bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getNextTaskReturns204WhenNoTask() {
        when(service.validateToken("token")).thenReturn(true);
        when(service.getNextTask("token")).thenReturn(Optional.empty());

        ResponseEntity<ClaudeAutomationController.TaskResponse> response =
                controller.getNextTask("token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void getNextTaskReturns200WithTaskDetails() {
        when(service.validateToken("token")).thenReturn(true);

        Anforderung task = new Anforderung();
        task.setId(1L);
        task.setTitel("Fix bug");
        task.setBeschreibung("Details");
        when(service.getNextTask("token")).thenReturn(Optional.of(task));

        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setPromptNumber("00001");
        prompt.setPromptText("prompt text");
        prompt.setLockfilePath("/tmp/lock");
        when(service.createPrompt(1L)).thenReturn(prompt);

        ResponseEntity<ClaudeAutomationController.TaskResponse> response =
                controller.getNextTask("token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAnforderungId()).isEqualTo(1L);
        assertThat(response.getBody().getTitel()).isEqualTo("Fix bug");
        assertThat(response.getBody().getPromptNumber()).isEqualTo("00001");
        verify(service).markPromptSent("00001");
    }

    // --- getNextPromptNumber ---

    @Test
    void getNextPromptNumberReturns401ForInvalidToken() {
        when(service.validateToken("bad")).thenReturn(false);

        ResponseEntity<Map<String, String>> response =
                controller.getNextPromptNumber("bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getNextPromptNumberReturns401WhenNoMandat() {
        when(service.validateToken("token")).thenReturn(true);
        when(service.getMandatFromToken("token")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response =
                controller.getNextPromptNumber("token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getNextPromptNumberReturns200WithNumber() {
        when(service.validateToken("token")).thenReturn(true);
        when(service.getMandatFromToken("token")).thenReturn(Optional.of("mandatA"));
        when(service.getNextPromptNumber("mandatA")).thenReturn("00042");

        ResponseEntity<Map<String, String>> response =
                controller.getNextPromptNumber("token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("promptNumber")).isEqualTo("00042");
    }

    // --- saveSummary ---

    @Test
    void saveSummaryReturns200OnSuccess() {
        when(service.saveSummary("00001", "summary", "token")).thenReturn(true);

        ResponseEntity<ClaudeAutomationController.SummaryResponse> response =
                controller.saveSummary("00001", "token", "summary");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isSuccess()).isTrue();
    }

    @Test
    void saveSummaryReturns400OnFailure() {
        when(service.saveSummary("00001", "summary", "bad")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.SummaryResponse> response =
                controller.saveSummary("00001", "bad", "summary");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- saveUserAnswer ---

    @Test
    void saveUserAnswerReturns200OnSuccess() {
        when(service.saveUserAnswer(1L, "answer", "token")).thenReturn(true);

        ResponseEntity<ClaudeAutomationController.AnswerResponse> response =
                controller.saveUserAnswer(1L, "token", "answer");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isSuccess()).isTrue();
    }

    @Test
    void saveUserAnswerReturns400OnFailure() {
        when(service.saveUserAnswer(1L, "answer", "bad")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.AnswerResponse> response =
                controller.saveUserAnswer(1L, "bad", "answer");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- getFullContext ---

    @Test
    void getFullContextReturns401WhenContextIsNull() {
        when(service.getFullContextForAnforderung(1L, "bad")).thenReturn(null);

        ResponseEntity<ClaudeAutomationController.ContextResponse> response =
                controller.getFullContext(1L, "bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getFullContextReturns200WithContext() {
        when(service.getFullContextForAnforderung(1L, "token")).thenReturn("full context");

        ResponseEntity<ClaudeAutomationController.ContextResponse> response =
                controller.getFullContext(1L, "token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContext()).isEqualTo("full context");
    }

    // --- updateStatus ---

    @Test
    void updateStatusReturns200OnSuccess() {
        when(service.updateAnforderungStatus(1L, "ERLEDIGT", "token")).thenReturn(true);

        ResponseEntity<ClaudeAutomationController.StatusUpdateResponse> response =
                controller.updateStatus(1L, "token", "ERLEDIGT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isSuccess()).isTrue();
    }

    @Test
    void updateStatusReturns400OnFailure() {
        when(service.updateAnforderungStatus(1L, "INVALID", "token")).thenReturn(false);

        ResponseEntity<ClaudeAutomationController.StatusUpdateResponse> response =
                controller.updateStatus(1L, "token", "INVALID");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- createAnforderung ---

    @Test
    void createAnforderungReturns200OnSuccess() {
        when(service.createAnforderung("token", "Title", "desc", "HOCH", "cat", false))
                .thenReturn(42L);

        ResponseEntity<ClaudeAutomationController.CreateAnforderungResponse> response =
                controller.createAnforderung("token", "Title", "desc", "HOCH", "cat", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getAnforderungId()).isEqualTo(42L);
    }

    @Test
    void createAnforderungReturns400OnFailure() {
        when(service.createAnforderung("bad", "Title", null, null, null, null))
                .thenReturn(null);

        ResponseEntity<ClaudeAutomationController.CreateAnforderungResponse> response =
                controller.createAnforderung("bad", "Title", null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    // --- getAllHowtos ---

    @Test
    void getAllHowtosReturns401ForInvalidToken() {
        when(service.validateToken("bad")).thenReturn(false);

        ResponseEntity<?> response = controller.getAllHowtos("bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getAllHowtosReturns200WithHowtos() {
        when(service.validateToken("token")).thenReturn(true);
        Howto h = new Howto();
        h.setName("test");
        when(howtoRepository.findByActiveTrue()).thenReturn(List.of(h));

        ResponseEntity<?> response = controller.getAllHowtos("token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- getHowtosForAnforderung ---

    @Test
    void getHowtosForAnforderungReturns401ForInvalidToken() {
        when(service.validateToken("bad")).thenReturn(false);

        ResponseEntity<?> response = controller.getHowtosForAnforderung(1L, "bad");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getHowtosForAnforderungReturns200() {
        when(service.validateToken("token")).thenReturn(true);
        when(service.getHowtosForAnforderung(1L)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getHowtosForAnforderung(1L, "token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
