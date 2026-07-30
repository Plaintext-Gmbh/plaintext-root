# Architecture Documentation

## Overview

Plaintext Root is a modular Jakarta Faces (JSF) application framework built on Spring Boot. It provides a complete foundation for building multi-tenant web applications with pre-built admin functionality, security, and a pluggable template system.

## System Architecture

```mermaid
C4Context
    title Plaintext Root - System Context

    Person(user, "User", "Application user")
    Person(admin, "Admin", "Administrator")

    System(plaintext, "Plaintext Root App", "Multi-tenant web application")
    System_Ext(postgres, "PostgreSQL", "Primary database")
    System_Ext(smtp, "SMTP Server", "Email sending")
    System_Ext(imap, "IMAP Server", "Email receiving")

    Rel(user, plaintext, "Uses", "HTTPS")
    Rel(admin, plaintext, "Manages", "HTTPS")
    Rel(plaintext, postgres, "Reads/Writes", "JDBC")
    Rel(plaintext, smtp, "Sends emails", "SMTP")
    Rel(plaintext, imap, "Receives emails", "IMAP")
```

## Module Architecture

```mermaid
graph LR
    subgraph "Presentation"
        TPL[Template Module<br/><i>UI Layout, CSS, JS</i>]
        WEB[Webapp Module<br/><i>Security, Login, Config</i>]
    end

    subgraph "Business"
        MENU[Menu Module<br/><i>Dynamic Navigation</i>]
        MS[Menuesteuerung<br/><i>Visibility Control</i>]
        ROLE[Rollenzuteilung<br/><i>Role Management</i>]
        EMAIL[Email Module<br/><i>SMTP/IMAP</i>]
    end

    subgraph "Infrastructure"
        JPA[JPA Module<br/><i>Base Entities, Audit</i>]
        COMMON[Common Module<br/><i>Utilities</i>]
        IFACE[Interfaces Module<br/><i>Contracts</i>]
        FW[Flyway Module<br/><i>Migrations</i>]
    end

    subgraph "Admin"
        A1[Settings]
        A2[Sessions]
        A3[Cron Jobs]
        A4[Wertelisten]
        A5[Filelist]
        A6[Anforderungen]
    end

    WEB --> TPL
    WEB --> MENU
    WEB --> MS
    WEB --> ROLE
    WEB --> EMAIL
    WEB --> A1 & A2 & A3 & A4

    MENU --> IFACE
    MS --> IFACE
    ROLE --> IFACE
    JPA --> COMMON
    EMAIL --> JPA
```

## Multi-Tenancy Architecture

```mermaid
sequenceDiagram
    participant User
    participant Security
    participant SuperModel
    participant Database

    User->>Security: Login
    Security->>Security: Authenticate + Load Mandate
    Security-->>User: Session with Mandate

    User->>SuperModel: Create Entity
    SuperModel->>SuperModel: Auto-set mandate field
    SuperModel->>Database: INSERT with mandate

    User->>SuperModel: Query Entities
    SuperModel->>Database: SELECT WHERE mandate = ?
    Database-->>User: Filtered Results
```

Every entity extending `SuperModel` automatically:
- Gets `mandat` field set on creation
- Gets `createdDate` and `lastModifiedDate` audit fields
- Can be filtered by mandate for data isolation

## Request Flow

```mermaid
sequenceDiagram
    participant Browser
    participant JSF as Jakarta Faces
    participant Security as Spring Security
    participant Bean as Backing Bean
    participant Service
    participant DB as PostgreSQL

    Browser->>JSF: HTTP Request (*.xhtml)
    JSF->>Security: Authentication Check
    Security->>Security: CSRF Validation

    alt Authenticated
        Security->>JSF: Allow
        JSF->>Bean: Invoke Backing Bean
        Bean->>Service: Business Logic
        Service->>DB: JPA Query
        DB-->>Service: Result
        Service-->>Bean: Data
        Bean-->>JSF: Update View
        JSF-->>Browser: Rendered HTML
    else Not Authenticated
        Security-->>Browser: Redirect to /login.xhtml
    end
```

## Menu System

```mermaid
graph TD
    A[Spring Context Scan] -->|Finds @Component| B[MenuItemImpl Beans]
    B --> C[MenuModelBuilder]
    C -->|Check roles| D{User has role?}
    D -->|Yes| E{Mandate visible?}
    D -->|No| F[Hidden]
    E -->|Yes| G[Add to MenuModel]
    E -->|No| F
    G --> H[Build Hierarchy<br/>parent/child]
    H --> I[Sort by order]
    I --> J[PrimeFaces MenuModel]
    J --> K[Rendered in Template]
```

Menu items are:
1. Discovered via Spring component scanning
2. Filtered by user roles
3. Filtered by mandate visibility
4. Organized into parent/child hierarchy
5. Sorted by order property
6. Rendered via the template's menu component

## Database Schema (Core)

```mermaid
erDiagram
    USER_SESSION {
        long id PK
        string session_id UK
        long user_id
        string username
        string mandat
        timestamp login_time
        timestamp last_activity_time
        string user_agent
        boolean active
    }

    USER_PREFERENCE {
        string user PK
        string menu_mode
        string dark_mode
        string component_theme
        string input_style
        boolean menu_static
    }

    WERTELISTE {
        long id PK
        string key
        string mandat
        text data
    }

    CRON_STATISTIC {
        long id PK
        string cron_name
        int counter
        timestamp last_run
    }
```

## Template System

```mermaid
graph TB
    subgraph "Maven Dependencies"
        APP[plaintext-root-webapp]
        TPL_P[template-plaintext<br/><i>Open Source</i>]
    end

    APP -->|Default| TPL_P
    APP -.->|Swap in pom.xml| TPL_F

    subgraph "Template Contents"
        T1[template.xhtml<br/>Main layout]
        T2[topbar.xhtml<br/>Navigation bar]
        T3[menu.xhtml<br/>Sidebar menu]
        T4[config.xhtml<br/>Settings panel]
        T5[footer.xhtml<br/>Footer]
        T6[layout.js<br/>Layout controller]
        T7[layout-light.css<br/>Light theme]
        T8[layout-dark.css<br/>Dark theme]
    end

    TPL_P --> T1 & T2 & T3 & T4 & T5 & T6 & T7 & T8
```

Templates are swapped by changing a single Maven dependency. Both templates provide identical file paths under `META-INF/resources/`, so no code changes are needed in consuming applications.

## Security Architecture

```mermaid
graph TD
    A[HTTP Request] --> B{Spring Security Filter}
    B -->|Public URLs| C[Allow]
    B -->|Protected URLs| D{Authenticated?}
    D -->|No| E[Redirect to Login]
    D -->|Yes| F{CSRF Valid?}
    F -->|No| G[403 Forbidden]
    F -->|Yes| H{Has Required Role?}
    H -->|No| I[Access Denied Page]
    H -->|Yes| J{Page Access Guard}
    J -->|Menu not visible| K[Redirect to Home]
    J -->|Allowed| L[Render Page]

    subgraph "Roles"
        R1[ROLE_USER]
        R2[ROLE_ADMIN]
        R3[ROLE_ROOT]
    end

    R1 -->|Base access| H
    R2 -->|Admin panels| H
    R3 -->|Full access + mandate switch| H
```

## Deployment

```mermaid
graph LR
    subgraph "Development"
        DEV[mvn spring-boot:run]
        COMPOSE[docker compose up]
    end

    subgraph "Production"
        BUILD[mvn clean package]
        DOCKER[Docker Image<br/>eclipse-temurin:25-jre]
        NGINX[Nginx Reverse Proxy<br/>Blue/Green Deploy]
    end

    DEV --> COMPOSE
    BUILD --> DOCKER
    DOCKER --> NGINX
```

The project includes:
- `compose.yaml` for local development (PostgreSQL)
- `Dockerfile` for production container builds
- `deploy/docker-compose-bluegreen.yaml` for blue/green deployments with Nginx
