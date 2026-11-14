package com.example.identity.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Locale;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String WORK_ORDER_CLIENT = "work-order-demo";
    static final String HR_CLIENT = "hr-demo";
    private static final String DEFAULT_CALLBACK = "http://127.0.0.1:8081/login/oauth2/code/work-order";
    private static final String DEFAULT_HR_CALLBACK = "http://127.0.0.1:8082/login/oauth2/code/hr";

    @Bean
    @Order(1)
    SecurityFilterChain oidcEndpoints(HttpSecurity http, HrApplicationAccess hrAccess) throws Exception {
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            server.oidc(Customizer.withDefaults())
                    .authorizationEndpoint(endpoint -> endpoint.authenticationProviders(providers -> {
                        for (var provider : providers) {
                            if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
                                codeProvider.setAuthenticationValidator(context -> {
                                    requireExactCallback(context);
                                    OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
                                    requireHrAccess(context, hrAccess);
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
        // Only admin page navigation may replace a saved authorization request on this chain.
        var siteRequests = new HttpSessionRequestCache();
        siteRequests.setRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/admin/**"));
        http.requestCache(cache -> cache.requestCache(siteRequests));
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/", "/login", "/style.css", "/admin.css", "/error",
                        "/.well-known/appspecific/com.chrome.devtools.json").permitAll()
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
    @DependsOnDatabaseInitialization
    UserDetailsService users(PasswordEncoder encoder, EmployeeDirectory employees,
                             @Value("${demo.employee-password}") String employeePassword,
                             @Value("${demo.admin-password}") String adminPassword) {
        employees.initializeDemoEmployee(employeePassword);
        String adminPasswordHash = encoder.encode(adminPassword);
        return username -> {
            String account = username.toLowerCase(Locale.ROOT);
            if (EmployeeDirectory.ADMIN_USERNAME.equals(account)) {
                return User.withUsername(account).password(adminPasswordHash).roles("ADMIN").build();
            }
            return employees.find(account)
                    .map(employee -> User.withUsername(employee.username())
                            .password(employee.passwordHash()).roles("EMPLOYEE").build())
                    .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        };
    }

    @Bean
    @DependsOnDatabaseInitialization
    RegisteredClientRepository registeredClients(JdbcOperations jdbc, PasswordEncoder encoder,
                                                 @Value("${demo.client-secret}") String secret,
                                                 @Value("${demo.hr-client-secret}") String hrSecret) {
        var clients = new JdbcRegisteredClientRepository(jdbc);
        initializeClient(clients, encoder, WORK_ORDER_CLIENT, "工单演示", DEFAULT_CALLBACK, secret);
        initializeClient(clients, encoder, HR_CLIENT, "人事演示", DEFAULT_HR_CALLBACK, hrSecret);
        return clients;
    }

    private static void initializeClient(RegisteredClientRepository clients, PasswordEncoder encoder,
                                         String clientId, String name, String callback, String secret) {
        RegisteredClient existing = clients.findByClientId(clientId);
        if (existing != null) {
            if (!encoder.matches(secret, existing.getClientSecret())) {
                clients.save(RegisteredClient.from(existing).clientSecret(encoder.encode(secret)).build());
            }
            return;
        }

        clients.save(webClient(clientId, name, callback, encoder.encode(secret)));
    }

    static RegisteredClient webClient(String clientId, String name, String callback, String secretHash) {
        return RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientName(name)
                .clientSecret(secretHash)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callback)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().authorizationCodeTimeToLive(Duration.ofMinutes(2)).build())
                .build();
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

    private static void requireHrAccess(OAuth2AuthorizationCodeRequestAuthenticationContext context,
                                        HrApplicationAccess hrAccess) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        if (HR_CLIENT.equals(context.getRegisteredClient().getClientId())
                && request.getPrincipal() instanceof Authentication principal
                && principal.isAuthenticated()
                && principal.getAuthorities().stream().anyMatch(authority -> "ROLE_EMPLOYEE".equals(authority.getAuthority()))
                && !hrAccess.isGranted(principal.getName())) {
            // The callback was validated above, so the app can display the denial.
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED), request);
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
    OAuth2TokenCustomizer<JwtEncodingContext> employeeName(EmployeeDirectory employees) {
        return context -> {
            if ("id_token".equals(context.getTokenType().getValue())
                    && context.getAuthorizedScopes().contains(OidcScopes.PROFILE)) {
                employees.find(context.getPrincipal().getName())
                        .ifPresent(employee -> context.getClaims().claim("name", employee.displayName()));
            }
        };
    }
}
