package com.example.identity.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class ApplicationSettingsController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CALLBACK_ERROR =
            "请填写完整的 HTTPS 地址。本地演示可以使用 HTTP 和 127.0.0.1 或 localhost。地址不能包含片段、账号信息或逗号。";

    private final RegisteredClientRepository clients;
    private final JdbcOperations jdbc;
    private final PasswordEncoder encoder;

    ApplicationSettingsController(RegisteredClientRepository clients, JdbcOperations jdbc, PasswordEncoder encoder) {
        this.clients = clients;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @GetMapping("/admin/application")
    String application(Model model) {
        model.addAttribute("applications", jdbc.query("""
                SELECT client_id, client_name, redirect_uris
                FROM oauth2_registered_client
                ORDER BY client_id_issued_at, client_id
                """, (row, number) -> new ApplicationForm(row.getString("client_id"),
                row.getString("client_name"), row.getString("redirect_uris"))));
        return "application";
    }

    @PostMapping("/admin/application/create")
    String create(@RequestParam String clientName, @RequestParam String redirectUri, Model model,
                  RedirectAttributes redirect, HttpServletResponse response) {
        String name = clientName.strip();
        String address = redirectUri.strip();
        if (name.isEmpty() || name.length() > 80 || !isValidCallback(address)) {
            model.addAttribute("registrationError", name.isEmpty() || name.length() > 80
                    ? "应用名称须为 1–80 个字符。" : CALLBACK_ERROR);
            model.addAttribute("submittedClientName", name);
            model.addAttribute("submittedNewRedirectUri", address);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return application(model);
        }

        String clientId = "app-" + UUID.randomUUID();
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        clients.save(SecurityConfiguration.webClient(clientId, name, address, encoder.encode(secret)));
        redirect.addFlashAttribute("createdClientId", clientId);
        redirect.addFlashAttribute("createdClientSecret", secret);
        return "redirect:/admin/application";
    }

    @PostMapping("/admin/application")
    String save(@RequestParam(required = false) String clientId, @RequestParam String redirectUri,
                Model model, RedirectAttributes redirect,
                HttpServletResponse response) {
        String selectedClientId = clientId == null ? SecurityConfiguration.WORK_ORDER_CLIENT : clientId;
        RegisteredClient client = clients.findByClientId(selectedClientId);
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        String address = redirectUri.strip();
        if (!isValidCallback(address)) {
            model.addAttribute("errorClientId", selectedClientId);
            model.addAttribute("submittedRedirectUri", address);
            model.addAttribute("error", CALLBACK_ERROR);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return application(model);
        }

        clients.save(RegisteredClient.from(client).redirectUris(uris -> {
            uris.clear();
            uris.add(address);
        }).build());
        redirect.addFlashAttribute("savedClientId", selectedClientId);
        return "redirect:/admin/application";
    }

    record ApplicationForm(String clientId, String clientName, String redirectUri) {
    }

    private static boolean isValidCallback(String address) {
        if (address.length() > 1000 || address.contains(",")) {
            return false;
        }
        try {
            URI uri = new URI(address);
            String host = uri.getHost();
            if (host == null || uri.getRawFragment() != null || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                return false;
            }
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)));
        } catch (URISyntaxException invalidAddress) {
            return false;
        }
    }
}
