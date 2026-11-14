package com.example.identity.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkOrderController {

    private final String appLabel;
    private final String registrationId;
    private final String identityCenterUrl;

    WorkOrderController(@Value("${demo.app-label}") String appLabel,
            @Value("${demo.registration-id}") String registrationId,
            ClientRegistrationRepository registrations) {
        this.appLabel = appLabel;
        this.registrationId = registrationId;
        this.identityCenterUrl = registrations.findByRegistrationId(registrationId).getProviderDetails().getIssuerUri();
    }

    @GetMapping("/")
    String index(@AuthenticationPrincipal OidcUser employee, Model model) {
        model.addAttribute("appLabel", appLabel);
        model.addAttribute("registrationId", registrationId);
        model.addAttribute("identityCenterUrl", identityCenterUrl);
        if (employee != null) {
            String name = employee.getFullName();
            model.addAttribute("employeeName", StringUtils.hasText(name) ? name : employee.getSubject());
            model.addAttribute("employeeId", employee.getSubject());
        }
        return "work-order";
    }
}
