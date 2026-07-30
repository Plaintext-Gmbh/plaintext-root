/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import ch.plaintext.bus.ExecutionScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * BeanPostProcessor that wraps PlaintextCron implementations into SuperCron at runtime.
 * This allows cron jobs to only implement the simple PlaintextCron interface,
 * while automatically gaining all the functionality of SuperCron.
 *
 * @author : mad
 * @since : 30.11.2025
 */
@Slf4j
@Component
public class CronBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {

        // Check if bean implements PlaintextCron but is NOT already a SuperCron
        if (bean instanceof PlaintextCron && !(bean instanceof SuperCron)) {

            log.info("Wrapping PlaintextCron bean '{}' of type {} into SuperCron",
                     beanName, bean.getClass().getName());

            PlaintextCron cronLogic = (PlaintextCron) bean;
            Class<? extends PlaintextCron> originalClass = (Class<? extends PlaintextCron>) bean.getClass();

            // Create a SuperCron wrapper at runtime
            SuperCron wrapper = new SuperCron() {
                @Override
                public void run(String mandant) {
                    cronLogic.run(mandant);
                }

                @Override
                public void run(String mandant, String userId) {
                    // Delegate to the wrapped implementation (Task 005, ExecutionScope.PERSOENLICH) --
                    // ohne diese Delegation wuerde SuperCron.runProPersoenlich() immer die
                    // SuperCron-Default-Implementierung (= run(mandant), ignoriert userId) aufrufen,
                    // nie den echten Cron-Code je Benutzer.
                    cronLogic.run(mandant, userId);
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean isGlobal() {
                    // Delegate to the wrapped implementation
                    return cronLogic.isGlobal();
                }

                @Override
                public ExecutionScope getScope() {
                    // Delegate to the wrapped implementation (Task 005) -- ohne diese Delegation
                    // wuerde SuperCron.getScope() immer auf isGlobal() dieses Wrappers zurueckfallen
                    // (PlaintextCron-Default), nie die echte getScope()-Ueberschreibung des Crons sehen.
                    return cronLogic.getScope();
                }

                @Override
                public String getName() {
                    // Return the simple name of the original class, not the wrapper
                    return originalClass.getSimpleName();
                }

                @Override
                public String getDisplayName() {
                    // Delegate to the wrapped implementation
                    return cronLogic.getDisplayName();
                }

                @Override
                public String getDefaultCronExpression() {
                    // Delegate to the wrapped implementation
                    return cronLogic.getDefaultCronExpression();
                }
            };

            // Set Spring properties on the wrapper
            wrapper.setBeanName(beanName);
            wrapper.setApplicationContext(applicationContext);
            wrapper.setOriginalBeanClass(originalClass);

            // Validate the ORIGINAL bean is prototype-scoped: the per-mandant scheduling
            // logic in CronController calls ctx.getBean(beanName) repeatedly and assumes
            // each call returns a fresh instance. If the original bean is singleton,
            // setMandant() mutations leak across mandants and the cron silently runs
            // for the wrong mandant (regression observed when EmailReceiveCron lacked
            // @Scope("prototype")). Fail fast at startup instead.
            if (applicationContext.isSingleton(beanName)) {
                throw new IllegalStateException(
                        "PlaintextCron bean '" + beanName + "' (" + originalClass.getName() +
                        ") must be @Scope(\"prototype\"). A singleton cron would have its " +
                        "mandant overwritten by the last setMandant() call, causing it to " +
                        "execute for the wrong mandant.");
            }

            return wrapper;
        }

        return bean;
    }
}
