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

Check the application logs for the initial credentials, or configure auto-login for development:

```yaml
# application.yml
mad:
  autologin: true
```

## Project Structure

After cloning, the project looks like this:

```
plaintext-root/
├── plaintext-root-interfaces/          # Shared API contracts
├── plaintext-root-common/              # Common utilities
├── plaintext-root-jpa/                 # JPA base entities
├── plaintext-root-menu/                # Menu system
├── plaintext-root-menu-visibility/      # Menu visibility
├── plaintext-root-role-assignment/     # Role management
├── plaintext-root-flyway/              # DB migrations
├── plaintext-root-template/  # UI template
├── plaintext-root-webapp/              # Main application
├── plaintext-admin-*/                  # Admin modules
├── compose.yaml                        # Dev database
└── pom.xml                             # Parent POM
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
        setCommand("mypage.xhtml");
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
