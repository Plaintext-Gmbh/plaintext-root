# Security Policy

## Supported Versions

| Version         | Supported          |
| --------------- | ------------------ |
| latest release  | :white_check_mark: |
| anything older  | :x:                |

Only the latest release receives security fixes. Older releases must be
upgraded. Releases are published as GitHub releases and as Maven artifacts.

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do NOT** open a public GitHub issue.
2. Use GitHub's private vulnerability reporting
   ("Security" tab → "Report a vulnerability"), or send an email to
   **info@plaintext.ch** with:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
3. You can expect an acknowledgement within a few working days.
4. Allow reasonable time for a fix before public disclosure.

## Security Features

Plaintext Root includes several built-in security features:

- **Spring Security** integration with CSRF protection
- **Role-based access control** (ROLE_USER, ROLE_ADMIN, ROLE_ROOT)
- **Multi-tenancy isolation** (mandate-based data separation)
- **Session tracking** and audit logging
- **API token authentication** for REST endpoints
- **Secure cookie handling** for theme preferences
- **Page access guards** for menu-based navigation security
- **Authenticated config encryption** (AES/GCM with PBKDF2-HMAC-SHA256, see
  [docs/CRYPTO.md](docs/CRYPTO.md))
