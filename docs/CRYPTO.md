# Configuration Encryption

Plaintext Root encrypts sensitive configuration values (currently SMTP/IMAP
passwords) before they are written to the database. This document describes the
on-disk format, the migration path between versions, and how to operate the
migration utility.

## Algorithm

| Component         | Value                              |
| ----------------- | ---------------------------------- |
| Cipher            | AES/GCM/NoPadding (128-bit tag)    |
| Key length        | 256 bit                            |
| Key derivation    | PBKDF2WithHmacSHA256, 65536 rounds |
| Salt length       | 16 bytes (random per call)         |
| IV length         | 12 bytes (random per call)         |
| Authentication    | Built-in GCM authentication tag    |

The PBKDF2 password is provided by the operator (passed in by the caller). The
service does not store or read the password itself.

## Wire format

Encrypted values are stored as ASCII strings:

```
ENCv2[<base64(version || salt || iv || ciphertext || tag)>]
```

| Offset | Size       | Field          |
| ------ | ---------- | -------------- |
| 0      | 1 byte     | Version (0x02) |
| 1      | 16 bytes   | Salt           |
| 17     | 12 bytes   | IV             |
| 29..   | n bytes    | Ciphertext     |
| n+29   | 16 bytes   | GCM auth tag   |

## Legacy format (read-only)

Older releases used `AES/CBC/PKCS5Padding` and wrote values as:

```
ENC[<base64(salt || iv || ciphertext)>]
```

| Offset | Size       | Field             |
| ------ | ---------- | ----------------- |
| 0      | 16 bytes   | Salt              |
| 16     | 16 bytes   | IV                |
| 32..   | n bytes    | CBC ciphertext    |

`ConfigEncryptionService.decrypt(...)` accepts both formats so existing data is
readable without an immediate migration. New ciphertext is **always** written in
the GCM (`ENCv2[…]`) format.

## Migration

Use `ConfigEncryptionMigrator` to upgrade values stored in legacy format. The
migrator works with arbitrary getter/setter pairs so the same code path applies
to JPA entities, properties files, or operator config maps.

```java
@Autowired ConfigEncryptionMigrator migrator;

migrator.migrateField(
        emailConfig::getImapPassword,
        emailConfig::setImapPassword,
        keystorePassword);
migrator.migrateField(
        emailConfig::getSmtpPassword,
        emailConfig::setSmtpPassword,
        keystorePassword);
emailConfigRepository.save(emailConfig);
```

For batch migrations:

```java
ConfigEncryptionMigrator.Result result = migrator.migrateFields(List.of(
        new FieldMigration("imap", emailConfig::getImapPassword,  emailConfig::setImapPassword),
        new FieldMigration("smtp", emailConfig::getSmtpPassword,  emailConfig::setSmtpPassword)
), keystorePassword);
log.info("Encryption migration: {}", result);
```

The migrator reports `upgraded`, `skipped` (already current or plain text), and
`failed` counts. Individual failures are logged at WARN level and do not abort
the batch.

## Operational checklist for a release that flips on encryption

1. Deploy the release containing the new `ConfigEncryptionService`.
2. From an admin endpoint, invoke the migrator over every encrypted field in
   every tenant DB.
3. Verify there are no `ENC[…]` (without `v2`) values remaining:
   ```sql
   SELECT mandat, count(*)
     FROM email_config_v2
    WHERE imap_password LIKE 'ENC[%' AND imap_password NOT LIKE 'ENCv2[%';
   ```
4. Once empty, the legacy decrypt path can be removed in a follow-up release.

## Related

- Issue [#116](https://github.com/Plaintext-Gmbh/plaintext-root/issues/116) —
  CodeQL `java/weak-cryptographic-algorithm` migration.
