package com.example.identity.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class EmployeeManagementController {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,49}");

    private final EmployeeDirectory employees;
    private final HrApplicationAccess hrAccess;

    EmployeeManagementController(EmployeeDirectory employees, HrApplicationAccess hrAccess) {
        this.employees = employees;
        this.hrAccess = hrAccess;
    }

    @GetMapping("/admin/employees")
    String employees(@RequestParam(defaultValue = "") String q, Model model) {
        String query = searchQuery(q);
        model.addAttribute("query", query);
        model.addAttribute("employeeCount", employees.count());
        model.addAttribute("employees", employees.search(query));
        return "employees";
    }

    @PostMapping("/admin/employees")
    String create(@RequestParam String username, @RequestParam String displayName, @RequestParam String password,
                  Model model, RedirectAttributes redirect, HttpServletResponse response) {
        String account = username.strip().toLowerCase(Locale.ROOT);
        String name = displayName.strip();
        String error = validate(account, name, password);
        int status = HttpServletResponse.SC_BAD_REQUEST;
        if (error == null) {
            try {
                employees.create(account, name, password);
            } catch (DuplicateKeyException duplicate) {
                error = "此登录账号已存在。";
                status = HttpServletResponse.SC_CONFLICT;
            }
        }
        if (error != null) {
            model.addAttribute("username", account);
            model.addAttribute("displayName", name);
            model.addAttribute("error", error);
            response.setStatus(status);
            return employees("", model);
        }
        redirect.addFlashAttribute("created", account);
        redirect.addAttribute("q", account);
        return "redirect:/admin/employees";
    }

    @PostMapping("/admin/employees/hr-access")
    String updateHrAccess(@RequestParam String username, @RequestParam boolean allowed,
                          @RequestParam(defaultValue = "") String q, RedirectAttributes redirect) {
        if (employees.find(username).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        hrAccess.setGranted(username, allowed);
        redirect.addFlashAttribute("accessSaved", username);
        String query = searchQuery(q);
        if (!query.isEmpty()) {
            redirect.addAttribute("q", query);
        }
        return "redirect:/admin/employees";
    }

    private static String searchQuery(String query) {
        String stripped = query.strip();
        return stripped.length() > 100 ? stripped.substring(0, 100) : stripped;
    }

    private static String validate(String username, String displayName, String password) {
        if (!USERNAME.matcher(username).matches()) {
            return "账号须为 3–50 位字母、数字、点、下划线或连字符，且以字母或数字开头。";
        }
        if (EmployeeDirectory.ADMIN_USERNAME.equals(username)) {
            return "该账号为管理员保留，请使用其他账号。";
        }
        if (displayName.isEmpty() || displayName.length() > 80) {
            return "姓名须为 1–80 个字符。";
        }
        if (password.isBlank() || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return "密码至少需要 8 个字符，最多 72 字节。";
        }
        return null;
    }
}
