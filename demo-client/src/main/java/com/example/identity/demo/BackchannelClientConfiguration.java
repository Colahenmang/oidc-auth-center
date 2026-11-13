package com.example.identity.demo;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("demo.auth.backchannel")
@EnableConfigurationProperties(OAuth2ClientProperties.class)
class BackchannelClientConfiguration {

    @Bean
    ClientRegistrationRepository containerClientRegistration(OAuth2ClientProperties properties,
            @Value("${demo.auth.backchannel}") URI backchannel,
            @Value("${demo.registration-id}") String registrationId) {
        var client = properties.getRegistration().get(registrationId);
        String issuer = properties.getProvider().get(client.getProvider()).getIssuerUri();

        // Keep the browser URL and token issuer public. Server-to-server requests use Compose DNS.
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId(client.getClientId())
                .clientName(client.getClientName())
                .clientSecret(client.getClientSecret())
                .clientAuthenticationMethod(new ClientAuthenticationMethod(client.getClientAuthenticationMethod()))
                .authorizationGrantType(new AuthorizationGrantType(client.getAuthorizationGrantType()))
                .redirectUri(client.getRedirectUri())
                .scope(client.getScope())
                .issuerUri(issuer)
                .authorizationUri(issuer + "/oauth2/authorize")
                .tokenUri(backchannel.resolve("/oauth2/token").toString())
                .jwkSetUri(backchannel.resolve("/oauth2/jwks").toString())
                .userInfoUri(backchannel.resolve("/userinfo").toString())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
