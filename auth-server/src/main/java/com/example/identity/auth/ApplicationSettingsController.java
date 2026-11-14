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
    private static final String MANUAL_CLIENT_PREFIX = "app-";
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

        String clientId = MANUAL_CLIENT_PREFIX + UUID.randomUUID();
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

        // An edit submitted concurrently with deletion must never recreate the client.
        int updated = jdbc.update("UPDATE oauth2_registered_client SET redirect_uris = ? WHERE client_id = ?",
                address, selectedClientId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        redirect.addFlashAttribute("savedClientId", selectedClientId);
        return "redirect:/admin/application";
    }

    @GetMapping("/admin/application/delete")
    String confirmDelete(@RequestParam String clientId, Model model, HttpServletResponse response) {
        if (!isManuallyRegistered(clientId)) {
            return deletionError(model, response, HttpStatus.FORBIDDEN, "预置应用不可删除。");
        }
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            return deletionError(model, response, HttpStatus.NOT_FOUND, "应用已删除或不存在。");
        }
        model.addAttribute("deletionCandidate", new ApplicationForm(clientId, client.getClientName(), null));
        return application(model);
    }

    @PostMapping("/admin/application/delete")
    String delete(@RequestParam String clientId, @RequestParam(defaultValue = "false") boolean confirmed,
                  Model model, RedirectAttributes redirect,
                  HttpServletResponse response) {
        if (!isManuallyRegistered(clientId)) {
            return deletionError(model, response, HttpStatus.FORBIDDEN, "预置应用不可删除。");
        }
        if (!confirmed) {
            return deletionError(model, response, HttpStatus.BAD_REQUEST, "请先打开确认页面并确认删除。");
        }
        int deleted = jdbc.update("DELETE FROM oauth2_registered_client WHERE client_id = ?", clientId);
        if (deleted == 0) {
            return deletionError(model, response, HttpStatus.NOT_FOUND, "应用已删除或不存在。");
        }
        redirect.addFlashAttribute("applicationDeleted", true);
        return "redirect:/admin/application";
    }

    private String deletionError(Model model, HttpServletResponse response, HttpStatus status, String message) {
        model.addAttribute("deletionError", message);
        response.setStatus(status.value());
        return application(model);
    }

    private static boolean isManuallyRegistered(String clientId) {
        return clientId.startsWith(MANUAL_CLIENT_PREFIX);
    }

    record ApplicationForm(String clientId, String clientName, String redirectUri) {

        public boolean deletable() {
            return isManuallyRegistered(clientId);
        }
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
