# plaintext-root-webapp

## Purpose
Main web application module containing the application entry point, security configuration, JSF backing beans, and core web infrastructure. This is the executable module that ties all other modules together.

## Key Features
- Spring Boot application entry point
- Spring Security configuration
- JSF/PrimeFaces integration
- User authentication and session management
- OpenAPI/Swagger documentation
- Dashboard and navigation
- User profile management
- Object store for generic entities

## Main Components
### Core
- **Index**: Application entry point and home controller

### Security
- **SpringSecurityProvider**: Security integration
- **MyUserDetailsService**: User authentication service
- **MyUserRepository**: User data access
- **MyRememberMe**: Remember-me authentication

### Configuration
- **OpenApiConfig**: Swagger/OpenAPI configuration
- **SwaggerJsfBypassConfig**: JSF/Swagger integration
- **UrlRewriteConfig**: URL rewriting rules

### UI Components
- **DashboardController**: Main dashboard
- **MenuBean**: Navigation menu management
- **MandateBackingBean**: Mandate selection UI
- **UserPrefsSimpleStorage**: User preferences

### Object Store
- **SimpleStorableEntity**: Generic entity storage
- **SimpleStorableEntityRepository**: Generic repository
- **GenericEntityService**: Generic CRUD operations

### JPA Converters
- **ListConverter**, **SetConverter**: Collection converters
- **XstreamBaseJPAConverter**: XStream-based conversion
- **MyUserXstreamBaseJPAConverter**, **MyUserSetConverter**: User-specific converters

### Performance
- **PerformanceService**: Performance monitoring
- **TimingAspect**: Method execution timing

### Menus
- **HomeMenu**: Home navigation
- **RootSuperMenu**: Root menu
- **AdminSuperMenu**: Admin menu
- **DebugMenu**: Debug tools menu
- **PerformanceMenu**: Performance monitoring menu
- **MandateMenu**: Mandate selection menu

## Dependencies
### External Dependencies
- Spring Boot (web, security, data-jpa)
- PrimeFaces Spring Boot Starter
- Jakarta Faces
- SpringDoc OpenAPI
- Lombok
- H2 (PostgreSQL mode)

### Internal Module Dependencies
- plaintext-root-interfaces
- plaintext-root-common
- plaintext-root-menu
- plaintext-root-jpa
- plaintext-root-flyway
- All admin modules
- All z-modules

## Configuration
### Required Properties
- Database connection settings
- Security settings (CSRF, session timeout)
- JSF configuration
- OpenAPI endpoints

### Optional Settings
- Performance monitoring
- Debug mode
- Remember-me configuration
- Mandate settings

## Entry Points
- **Main Class**: `Index` (Spring Boot application)
- **REST Controllers**: DashboardController, SwaggerRedirectController, XhtmlDebugController
- **JSF Pages**: /index.xhtml, /dashboard.xhtml
- **OpenAPI**: /swagger-ui.html, /v3/api-docs

## Important Notes
- This is the only executable module (contains @SpringBootApplication)
- All JSF forms must include CSRF token: `<input type="hidden" name="_csrf" value="#{_csrf.token}"/>`
- Use `<h:form id="fm">` for JSF forms
