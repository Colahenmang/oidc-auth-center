package com.example.identity.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class EmployeeDirectory {

    static final String DEMO_EMPLOYEE = "employee";
    static final String ADMIN_USERNAME = "admin";

    private final JdbcOperations jdbc;
    private final PasswordEncoder passwords;

    EmployeeDirectory(JdbcOperations jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    Optional<EmployeeAccount> find(String username) {
        return jdbc.query("""
                SELECT username, display_name, password_hash FROM demo_employee WHERE username = ?
                """, (row, index) -> new EmployeeAccount(row.getString(1), row.getString(2), row.getString(3)),
                username).stream().findFirst();
    }

    List<EmployeeRow> search(String query) {
        return jdbc.query("""
                SELECT employee.username, employee.display_name,
                    COALESCE(grant_record.access_granted, employee.username = ?) AS hr_access
                FROM demo_employee employee
                LEFT JOIN demo_application_access grant_record
                    ON grant_record.username = employee.username AND grant_record.client_id = ?
                WHERE ? = '' OR LOCATE(LOWER(?), employee.username) > 0
                    OR LOCATE(LOWER(?), LOWER(employee.display_name)) > 0
                ORDER BY employee.username
                """, (row, index) -> new EmployeeRow(row.getString(1), row.getString(2), row.getBoolean(3)),
                DEMO_EMPLOYEE, SecurityConfiguration.HR_CLIENT, query, query, query);
    }

    long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM demo_employee", Long.class);
    }

    void create(String username, String displayName, String password) {
        jdbc.update("""
                INSERT INTO demo_employee (username, display_name, password_hash) VALUES (?, ?, ?)
                """, username, displayName, passwords.encode(password));
    }

    void initializeDemoEmployee(String password) {
        var existing = find(DEMO_EMPLOYEE);
        if (existing.isEmpty()) {
            create(DEMO_EMPLOYEE, "小朱", password);
        } else if (!passwords.matches(password, existing.get().passwordHash())) {
            jdbc.update("UPDATE demo_employee SET password_hash = ? WHERE username = ?",
                    passwords.encode(password), DEMO_EMPLOYEE);
        }
    }

    record EmployeeAccount(String username, String displayName, String passwordHash) {}

    record EmployeeRow(String username, String displayName, boolean hrAccessGranted) {}
}
