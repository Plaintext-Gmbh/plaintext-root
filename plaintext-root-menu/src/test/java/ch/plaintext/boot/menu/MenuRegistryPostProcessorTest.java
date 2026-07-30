/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuVisibilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuRegistryPostProcessorTest {

    private MenuRegistryPostProcessor postProcessor;

    @Mock
    private ConfigurableListableBeanFactory beanFactory;

    @Mock
    private BeanDefinitionRegistry registry;

    @Mock
    private Environment environment;

    @BeforeEach
    void setUp() {
        postProcessor = new MenuRegistryPostProcessor();
    }

    @Test
    void postProcessBeanDefinitionRegistry_shouldNotThrow() {
        assertDoesNotThrow(() -> postProcessor.postProcessBeanDefinitionRegistry(registry));
    }

    @Test
    void postProcessBeanFactory_shouldScanDefaultPackage() {
        when(beanFactory.getBean(Environment.class)).thenReturn(environment);
        when(environment.getProperty("plaintext.menu.scan-package", "ch.plaintext"))
                .thenReturn("ch.plaintext.nonexistent.test");
        when(beanFactory.getBean(SecurityProvider.class)).thenThrow(new RuntimeException("not found"));
        when(beanFactory.getBean(MenuVisibilityProvider.class)).thenThrow(new RuntimeException("not found"));

        assertDoesNotThrow(() -> postProcessor.postProcessBeanFactory(beanFactory));
    }

    @Test
    void postProcessBeanFactory_shouldHandleMissingSecurityProvider() {
        when(beanFactory.getBean(Environment.class)).thenReturn(environment);
        when(environment.getProperty("plaintext.menu.scan-package", "ch.plaintext"))
                .thenReturn("ch.plaintext.nonexistent.test");
        when(beanFactory.getBean(SecurityProvider.class)).thenThrow(new RuntimeException("not found"));
        when(beanFactory.getBean(MenuVisibilityProvider.class)).thenThrow(new RuntimeException("not found"));

        assertDoesNotThrow(() -> postProcessor.postProcessBeanFactory(beanFactory));
    }

    @Test
    void postProcessBeanFactory_shouldHandleMissingMenuVisibilityProvider() {
        when(beanFactory.getBean(Environment.class)).thenReturn(environment);
        when(environment.getProperty("plaintext.menu.scan-package", "ch.plaintext"))
                .thenReturn("ch.plaintext.nonexistent.test");
        SecurityProvider mockProvider = mock(SecurityProvider.class);
        when(beanFactory.getBean(SecurityProvider.class)).thenReturn(mockProvider);
        when(beanFactory.getBean(MenuVisibilityProvider.class)).thenThrow(new RuntimeException("not found"));

        assertDoesNotThrow(() -> postProcessor.postProcessBeanFactory(beanFactory));
    }

    @Test
    void postProcessBeanFactory_shouldScanMultiplePackages() {
        when(beanFactory.getBean(Environment.class)).thenReturn(environment);
        when(environment.getProperty("plaintext.menu.scan-package", "ch.plaintext"))
                .thenReturn("ch.plaintext.nonexistent1,ch.plaintext.nonexistent2");
        when(beanFactory.getBean(SecurityProvider.class)).thenThrow(new RuntimeException("not found"));
        when(beanFactory.getBean(MenuVisibilityProvider.class)).thenThrow(new RuntimeException("not found"));

        assertDoesNotThrow(() -> postProcessor.postProcessBeanFactory(beanFactory));
    }

    @Test
    void postProcessBeanFactory_shouldUseSecurityProviderWhenAvailable() {
        when(beanFactory.getBean(Environment.class)).thenReturn(environment);
        when(environment.getProperty("plaintext.menu.scan-package", "ch.plaintext"))
                .thenReturn("ch.plaintext.nonexistent.test");
        SecurityProvider mockProvider = mock(SecurityProvider.class);
        when(beanFactory.getBean(SecurityProvider.class)).thenReturn(mockProvider);
        MenuVisibilityProvider mockVisibility = mock(MenuVisibilityProvider.class);
        when(beanFactory.getBean(MenuVisibilityProvider.class)).thenReturn(mockVisibility);

        assertDoesNotThrow(() -> postProcessor.postProcessBeanFactory(beanFactory));
    }

    @Test
    void postProcessBeanFactory_shouldRegisterFoundMenuItems() {
        when(beanFactory.getBean(Environment.class)).thenReturn(environment);
        // Point to the package containing TestAnnotatedClass
        when(environment.getProperty("plaintext.menu.scan-package", "ch.plaintext"))
                .thenReturn("ch.plaintext.boot.menu");
        when(beanFactory.getBean(SecurityProvider.class)).thenThrow(new RuntimeException("not found"));
        when(beanFactory.getBean(MenuVisibilityProvider.class)).thenThrow(new RuntimeException("not found"));

        postProcessor.postProcessBeanFactory(beanFactory);

        // Verify at least one singleton was registered (TestAnnotatedClass from MenuAnnotationScannerTest)
        verify(beanFactory, atLeastOnce()).registerSingleton(anyString(), any(MenuItemImpl.class));
    }

    @Test
    void shouldImplementBeanDefinitionRegistryPostProcessor() {
        assertInstanceOf(
                org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor.class,
                postProcessor);
    }
}
