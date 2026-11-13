package com.example.identity.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkOrderController {

    private final String appLabel;
    private final String registrationId;

    WorkOrderController(@Value("${demo.app-label}") String appLabel,
            @Value("${demo.registration-id}") String registrationId) {
        this.appLabel = appLabel;
        this.registrationId = registrationId;
    }

    @GetMapping("/")
    String index(@AuthenticationPrincipal OidcUser employee, Model model) {
        model.addAttribute("appLabel", appLabel);
        model.addAttribute("registrationId", registrationId);
        if (employee != null) {
            String name = employee.getFullName();
            model.addAttribute("employeeName", StringUtils.hasText(name) ? name : employee.getSubject());
            model.addAttribute("employeeId", employee.getSubject());
        }
        return "work-order";
    }
}
