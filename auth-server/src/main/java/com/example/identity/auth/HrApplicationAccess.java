package com.example.identity.auth;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

@Component
class HrApplicationAccess {

    private final JdbcOperations jdbc;

    HrApplicationAccess(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    boolean isGranted(String username) {
        return jdbc.query("""
                SELECT access_granted FROM demo_application_access
                WHERE client_id = ? AND username = ?
                """, rows -> rows.next() ? rows.getBoolean(1) : EmployeeDirectory.DEMO_EMPLOYEE.equals(username),
                SecurityConfiguration.HR_CLIENT, username);
    }

    void setGranted(String username, boolean allowed) {
        jdbc.update("""
                INSERT INTO demo_application_access (client_id, username, access_granted)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE access_granted = ?
                """, SecurityConfiguration.HR_CLIENT, username, allowed, allowed);
    }
}
