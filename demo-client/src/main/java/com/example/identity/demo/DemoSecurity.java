package com.example.identity.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.util.Assert;

@Configuration
public class DemoSecurity {

    @Bean
    SecurityFilterChain demoSecurityFilterChain(HttpSecurity http,
                                               ClientRegistrationRepository registrations,
                                               @Value("${demo.registration-id}") String registrationId) throws Exception {
        if ("finance".equals(registrationId)) {
            Assert.hasText(registrations.findByRegistrationId(registrationId).getClientSecret(),
                    "DEMO_FINANCE_CLIENT_SECRET is required for the finance demo");
        }

        var authorizationRequests = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, "/oauth2/authorization");
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        var loginFailure = new SimpleUrlAuthenticationFailureHandler("/?error");
        var applicationAccessDenied = new SimpleUrlAuthenticationFailureHandler("/?error=access_denied");
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/styles.css", "/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                        .defaultSuccessUrl("/", true)
                        .failureHandler((request, response, exception) -> {
                            if (!"work-order".equals(registrationId)
                                    && exception instanceof OAuth2AuthenticationException oauthError
                                    && OAuth2ErrorCodes.ACCESS_DENIED.equals(oauthError.getError().getErrorCode())) {
                                applicationAccessDenied.onAuthenticationFailure(request, response, exception);
                            } else {
                                loginFailure.onAuthenticationFailure(request, response, exception);
                            }
                        }))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .build();
    }
}
