package com.example.identity.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class DemoSecurity {

    @Bean
    SecurityFilterChain demoSecurityFilterChain(HttpSecurity http,
                                               ClientRegistrationRepository registrations) throws Exception {
        var authorizationRequests = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, "/oauth2/authorization");
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/styles.css", "/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?error"))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .build();
    }
}
