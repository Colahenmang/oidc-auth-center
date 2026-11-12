package com.example.identity.auth;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class SiteController {

    @GetMapping("/")
    String home(Principal principal, Model model) {
        model.addAttribute("signedInUsername", principal == null ? null : principal.getName());
        return "home";
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
