package com.example.identity.demo;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkOrderController {

    @GetMapping("/")
    String index(@AuthenticationPrincipal OidcUser employee, Model model) {
        if (employee != null) {
            String name = employee.getFullName();
            model.addAttribute("employeeName", StringUtils.hasText(name) ? name : employee.getSubject());
            model.addAttribute("employeeId", employee.getSubject());
        }
        return "work-order";
    }
}
