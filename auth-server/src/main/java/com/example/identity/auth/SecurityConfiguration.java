package com.example.identity.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String WORK_ORDER_CLIENT = "work-order-demo";
    private static final String DEFAULT_CALLBACK = "http://127.0.0.1:8081/login/oauth2/code/work-order";

    @Bean
    @Order(1)
    SecurityFilterChain oidcEndpoints(HttpSecurity http) throws Exception {
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            server.oidc(Customizer.withDefaults())
                    .authorizationEndpoint(endpoint -> endpoint.authenticationProviders(providers -> {
                        for (var provider : providers) {
                            if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
                                codeProvider.setAuthenticationValidator(context -> {
                                    requireExactCallback(context);
                                    OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
                                });
                            }
                        }
                    }));
        });
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain site(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/", "/login", "/style.css", "/error").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated());
        http.formLogin(login -> login.loginPage("/login").permitAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder,
                             @Value("${demo.employee-password}") String employeePassword,
                             @Value("${demo.admin-password}") String adminPassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername("employee").password(encoder.encode(employeePassword)).roles("EMPLOYEE").build(),
                User.withUsername("admin").password(encoder.encode(adminPassword)).roles("ADMIN").build());
    }

    @Bean
    @DependsOnDatabaseInitialization
    RegisteredClientRepository registeredClients(JdbcOperations jdbc, PasswordEncoder encoder,
                                                 @Value("${demo.client-secret}") String secret) {
        var clients = new JdbcRegisteredClientRepository(jdbc);
        RegisteredClient existing = clients.findByClientId(WORK_ORDER_CLIENT);
        if (existing != null) {
            if (!encoder.matches(secret, existing.getClientSecret())) {
                clients.save(RegisteredClient.from(existing).clientSecret(encoder.encode(secret)).build());
            }
            return clients;
        }

        RegisteredClient workOrder = RegisteredClient.withId(WORK_ORDER_CLIENT)
                .clientId(WORK_ORDER_CLIENT)
                .clientName("工单演示")
                .clientSecret(encoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(DEFAULT_CALLBACK)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().authorizationCodeTimeToLive(Duration.ofMinutes(2)).build())
                .build();
        clients.save(workOrder);
        return clients;
    }

    private static void requireExactCallback(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        if (!context.getRegisteredClient().getRedirectUris().contains(request.getRedirectUri())) {
            // Without an accepted callback, return the error at the authorization server.
            OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST,
                    "The redirect_uri is not registered for this application.", null);
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, null);
        }
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${demo.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet keySet = new JWKSet(signingKey);
        return (selector, context) -> selector.select(keySet);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> employeeName() {
        return context -> {
            if ("id_token".equals(context.getTokenType().getValue())
                    && context.getAuthorizedScopes().contains(OidcScopes.PROFILE)
                    && "employee".equals(context.getPrincipal().getName())) {
                context.getClaims().claim("name", "小朱");
            }
        };
    }
}
