/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;


import ch.plaintext.boot.plugins.security.lockout.AccountLockoutService;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MyUserDetailsService implements org.springframework.security.core.userdetails.UserDetailsService {

    /**
     * Authority, die einen erzwungenen Passwortwechsel markiert (Karte 306). Wird gesetzt, wenn
     * {@code MyUserEntity.mustChangePassword} true ist, und vom
     * {@code PlaintextAuthenticationSuccessHandler} in einen Redirect auf die Passwort-Seite umgesetzt.
     */
    public static final String MUST_CHANGE_PASSWORD_AUTHORITY = "PROPERTY_MUSTCHANGEPASSWORD";

    private final MyUserRepository userRepository;
    private final AccountLockoutService lockoutService;

    public MyUserDetailsService(MyUserRepository userRepository,
                                AccountLockoutService lockoutService) {
        this.userRepository = userRepository;
        this.lockoutService = lockoutService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("MyUserEntity not found");
        }
        if (user.isPasswordless()) {
            throw new UsernameNotFoundException("User is OIDC-only, password login disabled");
        }

        List<SimpleGrantedAuthority> auth = new ArrayList<>();
        for (String role : user.getRoles()) {
            if(role.toLowerCase().contains("mandat")){
                auth.add(new SimpleGrantedAuthority( role.toUpperCase()));
            } else {
                auth.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
            }
        }
        auth.add(new SimpleGrantedAuthority("PROPERTY_MYUSERID_" + user.getId()));
        if (user.getStartpage() != null && !user.getStartpage().isEmpty()) {
            auth.add(new SimpleGrantedAuthority("PROPERTY_STARTPAGE_"+user.getStartpage()));
        }
        // Karte 306: signalisiert dem AuthenticationSuccessHandler einen erzwungenen
        // Passwortwechsel (z.B. Root-Initialpasswort) -> Redirect auf die Selbstservice-Seite.
        if (user.isMustChangePassword()) {
            auth.add(new SimpleGrantedAuthority(MUST_CHANGE_PASSWORD_AUTHORITY));
        }

        boolean accountNonLocked = !lockoutService.isLocked(user.getUsername());

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                true,             // enabled
                true,             // accountNonExpired
                true,             // credentialsNonExpired
                accountNonLocked,
                auth
        );
    }
}
