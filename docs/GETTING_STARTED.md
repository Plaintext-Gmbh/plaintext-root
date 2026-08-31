# Getting Started

This guide walks you through setting up and running Plaintext Root for the first time.

## Prerequisites

| Tool | Version | Installation |
|------|---------|-------------|
| Java | 25+ | [SDKMAN](https://sdkman.io/): `sdk install java 25-open` |
| Maven | 3.9+ | [Download](https://maven.apache.org/download.cgi) |
| Docker/Podman | Latest | [Docker](https://docs.docker.com/get-docker/) |
| Git | Latest | [Download](https://git-scm.com/) |

## Step 1: Clone the Repository

```bash
git clone https://github.com/Plaintext-Gmbh/plaintext-root.git
cd plaintext-root
```

## Step 2: Start the Database

The project includes a `compose.yaml` for a local PostgreSQL instance:

```bash
docker compose up -d
```

This starts PostgreSQL on port **5434** with:
- Database: `plaintext_root`
- Username: `plaintext`
- Password: `plaintext`

## Step 3: Build the Project

```bash
mvn clean install -DskipTests
```

This builds all modules and installs them to your local Maven repository.

## Step 4: Run the Application

```bash
mvn spring-boot:run -pl plaintext-root-webapp
```

Or build and run as JAR:

```bash
java -jar plaintext-root-webapp/target/plaintext-root-webapp-*-exec.jar
```

The application starts at **http://localhost:8080**.

## Step 5: First Login

On first startup, Flyway automatically creates the database schema. A root user is created by the `PlaintextInitLoader`.

Check the application logs for the initial credentials. Scripted/kiosk access uses the regular
form login — the former `/token-login?token=` endpoint was removed in card 560; see
`docs/security/LOGIN_PATHS.md`.

## Project Structure

After cloning, the project looks like this:

```
plaintext-root/
├── plaintext-root-interfaces/       # contracts only — no dependencies
├── plaintext-root-common/           # security SPI, utilities, SuperModel
├── plaintext-root-menu/             # annotation-driven menu
├── plaintext-root-jpa/              # JPA base, auditing, entity registry
├── plaintext-root-flyway/           # framework migrations
├── plaintext-root-menu-visibility/  # per-tenant menu configuration
├── plaintext-root-role-assignment/  # assigning roles to users
├── plaintext-root-pageguard/        # per-view authorization
├── plaintext-root-web/              # URL rewrite, MenuBean
├── plaintext-root-template/         # UI template (no dependencies)
├── plaintext-root-webapp/           # the application shell — depends on 22 of the above
├── plaintext-root-archtests/        # shared ArchUnit rules, shipped in src/main
├── plaintext-admin-settings/        # ┐
├── plaintext-admin-sessions/        # │
├── plaintext-admin-cron/            # │
├── plaintext-admin-i18n/            # │
├── plaintext-admin-oidc/            # │ twelve admin modules, each removable
├── plaintext-admin-apitoken/        # │ with a Maven <exclusion> — see
├── plaintext-admin-secrets/         # │ docs/OPTIONAL_MODULES.md
├── plaintext-admin-modules/         # │
├── plaintext-admin-mailtemplate/    # │
├── plaintext-admin-webhooks/        # │
├── plaintext-admin-notifications/   # │
├── plaintext-admin-requirements/    # ┘ (the only one not in the aggregator)
├── .woodpecker/                     # CI pipelines
├── compose.yaml                     # dev database, PostgreSQL on 5434
└── pom.xml                          # parent POM
```

## Configuration

### Database

Override the database connection in `application.yml` or via environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://myhost:5432/mydb
export SPRING_DATASOURCE_USERNAME=myuser
export SPRING_DATASOURCE_PASSWORD=mypassword
```

### Theme

The default theme is the open-source Plaintext template. To customize:

1. **Dark/Light Mode**: Toggle via the gear icon in the top bar
2. **Menu Layout**: Choose Sidebar, Horizontal, or Slim
3. **Color Theme**: Select from 8 color options

## Adding a New Page

1. Create a `.xhtml` file in `src/main/resources/META-INF/resources/`:

```xml
<ui:composition template="includes/template.xhtml"
    xmlns="http://www.w3.org/1999/xhtml"
    xmlns:ui="http://java.sun.com/jsf/facelets"
    xmlns:h="http://java.sun.com/jsf/html">

    <ui:define name="content">
        <h:form id="fm">
            <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
            <h1>My New Page</h1>
        </h:form>
    </ui:define>
</ui:composition>
```

2. Create a menu item:

```java
@Component
public class MyPageMenu extends MenuItemImpl {
    public MyPageMenu() {
        setTitle("My Page");
        setParent("Admin");
        setCommand("mypage.html");
        setIcon("pi pi-star");
        setOrder(200);
    }
}
```

3. The page automatically appears in the menu after restart.

## Running Tests

```bash
# All tests
mvn test

# Single module
mvn test -pl plaintext-root-menu

# Skip tests for faster builds
mvn install -DskipTests
```

## Next Steps

- Read the [Architecture Documentation](ARCHITECTURE.md)
- Check [open issues](https://github.com/Plaintext-Gmbh/plaintext-root/issues) for contribution opportunities
- See [CONTRIBUTING.md](../CONTRIBUTING.md) for contribution guidelines
