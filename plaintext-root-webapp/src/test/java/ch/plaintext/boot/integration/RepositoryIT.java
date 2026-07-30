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
 * Integration test verifying JPA repositories work correctly
 * against a real PostgreSQL database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
@Transactional
class RepositoryIT {

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
    void saveAndFindUser() {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("testuser");
        user.setPassword("$2a$10$dummyhash");
        user.addRole("ROLE_USER");

        MyUserEntity saved = userRepository.save(user);
        assertNotNull(saved.getId());

        MyUserEntity found = userRepository.findByUsername("testuser");
        assertNotNull(found);
        assertEquals("testuser", found.getUsername());
        assertTrue(found.getRoles().contains("ROLE_USER"));
    }

    @Test
    void findByUsernameReturnsNullForUnknown() {
        MyUserEntity result = userRepository.findByUsername("nonexistent");
        assertNull(result);
    }

    @Test
    void userRolesArePersistedCorrectly() {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("multirole");
        user.setPassword("$2a$10$dummyhash");
        user.addRole("ROLE_USER");
        user.addRole("ROLE_ADMIN");
        user.addRole("PROPERTY_MANDAT_testmandat");

        userRepository.save(user);

        MyUserEntity found = userRepository.findByUsername("multirole");
        assertNotNull(found);
        assertEquals(3, found.getRoles().size());
        assertTrue(found.getRoles().contains("ROLE_ADMIN"));
        assertEquals("testmandat", found.getMandat());
    }

    @Test
    void autologinKeyLookupWorks() {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("autouser");
        user.setPassword("$2a$10$dummyhash");
        user.setAutologinKey("secret-key-123");
        user.addRole("ROLE_USER");

        userRepository.save(user);

        MyUserEntity found = userRepository.findByAutologinKey("secret-key-123");
        assertNotNull(found);
        assertEquals("autouser", found.getUsername());
    }
}
