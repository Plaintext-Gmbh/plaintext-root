/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying multi-tenancy data isolation
 * via the mandate system.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
@Transactional
class MultiTenancyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("plaintext_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private MyUserRepository userRepository;

    @Test
    void usersWithDifferentMandatesAreIsolated() {
        // Create user in mandate A
        MyUserEntity userA = new MyUserEntity();
        userA.setUsername("user-tenant-a");
        userA.setPassword("$2a$10$dummyhash");
        userA.addRole("ROLE_USER");
        userA.addRole("PROPERTY_MANDAT_tenantA");
        userRepository.save(userA);

        // Create user in mandate B
        MyUserEntity userB = new MyUserEntity();
        userB.setUsername("user-tenant-b");
        userB.setPassword("$2a$10$dummyhash");
        userB.addRole("ROLE_USER");
        userB.addRole("PROPERTY_MANDAT_tenantB");
        userRepository.save(userB);

        // Verify mandates are correctly assigned
        MyUserEntity foundA = userRepository.findByUsername("user-tenant-a");
        MyUserEntity foundB = userRepository.findByUsername("user-tenant-b");

        assertNotNull(foundA);
        assertNotNull(foundB);
        assertEquals("tenanta", foundA.getMandat());
        assertEquals("tenantb", foundB.getMandat());
        assertNotEquals(foundA.getMandat(), foundB.getMandat());
    }

    @Test
    void mandateIsExtractedFromRoles() {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("mandate-test-user");
        user.setPassword("$2a$10$dummyhash");
        user.addRole("ROLE_ADMIN");
        user.addRole("PROPERTY_MANDAT_mycompany");
        userRepository.save(user);

        MyUserEntity found = userRepository.findByUsername("mandate-test-user");
        assertNotNull(found);
        assertEquals("mycompany", found.getMandat());
    }

    @Test
    void userWithoutMandateHasNullMandat() {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("no-mandate-user");
        user.setPassword("$2a$10$dummyhash");
        user.addRole("ROLE_USER");
        userRepository.save(user);

        MyUserEntity found = userRepository.findByUsername("no-mandate-user");
        assertNotNull(found);
        assertNull(found.getMandat());
    }
}
