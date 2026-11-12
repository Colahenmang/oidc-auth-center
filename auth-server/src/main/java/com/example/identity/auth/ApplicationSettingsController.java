package com.example.identity.auth;

import java.net.URI;
import java.net.URISyntaxException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class ApplicationSettingsController {

    private final RegisteredClientRepository clients;

    ApplicationSettingsController(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @GetMapping("/admin/application")
    String application(Model model) {
        RegisteredClient client = workOrder();
        addForm(model, client, client.getRedirectUris().iterator().next());
        return "application";
    }

    @PostMapping("/admin/application")
    String save(@RequestParam String redirectUri, Model model, RedirectAttributes redirect,
                HttpServletResponse response) {
        RegisteredClient client = workOrder();
        String address = redirectUri.strip();
        if (!isValidCallback(address)) {
            addForm(model, client, address);
            model.addAttribute("error", "请填写完整的 HTTPS 地址。本地演示可以使用 HTTP 和 127.0.0.1 或 localhost。地址不能包含片段、账号信息或逗号。");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "application";
        }

        clients.save(RegisteredClient.from(client).redirectUris(uris -> {
            uris.clear();
            uris.add(address);
        }).build());
        redirect.addFlashAttribute("saved", true);
        return "redirect:/admin/application";
    }

    private RegisteredClient workOrder() {
        return clients.findByClientId(SecurityConfiguration.WORK_ORDER_CLIENT);
    }

    private static void addForm(Model model, RegisteredClient client, String redirectUri) {
        model.addAttribute("clientName", client.getClientName());
        model.addAttribute("clientId", client.getClientId());
        model.addAttribute("redirectUri", redirectUri);
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
