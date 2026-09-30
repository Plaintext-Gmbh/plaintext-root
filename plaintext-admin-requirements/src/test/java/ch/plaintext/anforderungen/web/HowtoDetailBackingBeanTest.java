/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.HowtoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1360 (HB1): {@code howtodetail.xhtml?id=} laedt das Howto nur im eigenen Mandanten.
 *
 * <p>Die Seite hat Schreibmethoden (Name, Text, Aktiv). Bis zum 30.09.2026 lud sie per
 * {@code findById} jedes Howto, dessen Id in der Adresse stand — auch das eines anderen Mandanten.
 */
@ExtendWith(MockitoExtension.class)
class HowtoDetailBackingBeanTest {

    @Mock
    private HowtoRepository howtoRepository;

    @Mock
    private PlaintextSecurity security;

    private HowtoDetailBackingBean bean;

    @BeforeEach
    void setUp() {
        bean = new HowtoDetailBackingBean();
        ReflectionTestUtils.setField(bean, "howtoRepository", howtoRepository);
        ReflectionTestUtils.setField(bean, "security", security);
        when(security.getMandat()).thenReturn("mandatA");
    }

    @Test
    void laedtEigenesHowto() {
        Howto eigen = new Howto();
        eigen.setId(1L);
        eigen.setMandat("mandatA");
        when(howtoRepository.findByIdAndMandat(1L, "mandatA")).thenReturn(Optional.of(eigen));

        bean.setHowtoId(1L);
        bean.onLoad();

        assertThat(bean.getHowto()).isSameAs(eigen);
    }

    @Test
    void fremdesHowtoGiltAlsNichtGefunden() {
        Howto fremd = new Howto();
        fremd.setId(2L);
        fremd.setMandat("mandatB");
        lenient().when(howtoRepository.findById(2L)).thenReturn(Optional.of(fremd));
        when(howtoRepository.findByIdAndMandat(2L, "mandatA")).thenReturn(Optional.empty());

        bean.setHowtoId(2L);
        bean.onLoad();

        assertThat(bean.getHowto()).isNull();
        verify(howtoRepository, never()).findById(anyLong());
    }
}
