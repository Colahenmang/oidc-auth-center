-- Registered client schema for Spring Security's JDBC repository.
CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id VARCHAR(100) PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL UNIQUE,
    client_id_issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret VARCHAR(200),
    client_secret_expires_at TIMESTAMP,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(1000),
    post_logout_redirect_uris VARCHAR(1000),
    scopes VARCHAR(1000) NOT NULL,
    client_settings VARCHAR(2000) NOT NULL,
    token_settings VARCHAR(2000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE IF NOT EXISTS demo_application_access (
    client_id VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    access_granted BOOLEAN NOT NULL,
    PRIMARY KEY (client_id, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE IF NOT EXISTS demo_employee (
    username VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
