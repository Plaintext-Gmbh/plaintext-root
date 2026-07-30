# Plaintext WebApp Menu

Programmatic menu builder for PrimeFaces using annotations.

## Overview

This module provides a declarative way to build PrimeFaces menus using annotations. Instead of defining menus in XHTML files, you can annotate classes with `@MenuAnnotation` and they will be automatically discovered and added to the menu.

## Usage

### 1. Add Dependency

Add this module as a dependency in your `pom.xml` (the version is inherited
from the `plaintext-root-parent` BOM, so omit it when consuming the framework
that way):

```xml
<dependency>
    <groupId>ch.plaintext</groupId>
    <artifactId>plaintext-root-menu</artifactId>
</dependency>
```

### 2. Annotate Classes

Create classes and annotate them with `@MenuAnnotation`:

```java
@MenuAnnotation(
    title = "Dashboard",
    link = "dashboard.xhtml",
    order = 10,
    icon = "pi pi-home"
)
public class DashboardMenu {
}

@MenuAnnotation(
    title = "Users",
    link = "users.xhtml",
    parent = "Administration",
    order = 20,
    icon = "pi pi-users",
    roles = {"ADMIN", "USER_MANAGER"}
)
public class UsersMenu {
}

@MenuAnnotation(
    title = "Administration",
    order = 100,
    icon = "pi pi-cog"
)
public class AdministrationMenu {
}
```

### 3. Build Menu Model

Inject `MenuModelBuilder` in your backing bean and build the menu.

For **JSF/Jakarta EE** applications:

```java
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named("menuBean")
@ApplicationScoped
public class MenuBean {

    @Inject
    private MenuModelBuilder menuModelBuilder;

    private MenuModel model;

    @PostConstruct
    public void init() {
        model = menuModelBuilder.buildMenuModel();
    }

    public MenuModel getModel() {
        return model;
    }
}
```

For **Spring Boot** applications:

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
public class MenuBean {

    @Autowired
    private MenuModelBuilder menuModelBuilder;

    private MenuModel model;

    @PostConstruct
    public void init() {
        model = menuModelBuilder.buildMenuModel();
    }

    public MenuModel getModel() {
        return model;
    }
}
```

### 4. Use in XHTML

Use the menu model in your XHTML template:

```xml
<p:menubar model="#{menuBean.model}" />
```

## Configuration

### Package Scanning

By default, the module scans the `ch.plaintext` package for annotated classes. You can change this in `application.properties`:

```properties
plaintext.menu.scan-package=ch.plaintext.myapp
```

### Security

To enable role-based menu visibility, implement the `SecurityProvider` interface and register it as a Spring bean:

```java
@Component
public class MySecurityProvider implements SecurityProvider {

    @Override
    public boolean hasRole(String role) {
        // Your security logic here
        return SecurityContextHolder.getContext()
            .getAuthentication()
            .getAuthorities()
            .stream()
            .anyMatch(a -> a.getAuthority().equals(role));
    }

    @Override
    public boolean isSecurityEnabled() {
        return true;
    }
}
```

## Annotation Parameters

- `title`: The menu item label (default: "Dashboard")
- `link`: The navigation outcome/URL (default: "dashboard.htm")
- `parent`: The parent menu item title for hierarchical menus (default: "")
- `order`: Sort order (lower numbers appear first, default: 0)
- `icon`: PrimeFaces icon class (default: "")
- `roles`: Array of roles that can see this menu item (default: empty = visible to all)

## How It Works

1. **MenuRegistryPostProcessor** scans the configured package for classes with `@MenuAnnotation`
2. Found annotations are converted to `MenuItemImpl` beans and registered in Spring context
3. **MenuModelBuilder** reads all `MenuItemImpl` beans and builds a hierarchical PrimeFaces `MenuModel`
4. Menu items are filtered based on security roles (if configured)
5. The resulting `MenuModel` can be used directly with PrimeFaces components

## Migration from XHTML Menus

If you're migrating from XHTML-defined menus:

1. For each menu item in your XHTML, create a corresponding annotated class
2. Map XHTML attributes to annotation parameters:
   - `value` → `title`
   - `outcome` → `link`
   - `rendered` → `roles` (use SecurityProvider)
3. Use the parent-child relationship via the `parent` parameter
4. Remove the menu definition from your XHTML template
5. Inject and use `MenuModelBuilder` instead

## Example

See the example classes in this package for a complete working example of a multi-level menu with security.
