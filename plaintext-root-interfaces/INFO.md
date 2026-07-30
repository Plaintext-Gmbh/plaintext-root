# plaintext-root-interfaces

## Purpose
Defines service interfaces, DTOs, and contracts used across all Plaintext modules. This module contains no implementation code, only interfaces and data transfer objects.

## Key Features
- Service interface definitions (ISettingsService, ChatService)
- Menu system interfaces (MenuVisibilityProvider, MenuRegistry, MenuAnnotation)
- Chat DTOs (ChatDTO, ChatMessageDTO, DirectMessageDTO, ChatInvitationDTO, ChatMessagesPageDTO)
- Cron scheduling interface (PlaintextCron)
- Cross-module communication contracts

## Main Components
- **Interfaces**: ISettingsService, ChatService, PlaintextCron
- **Menu System**: MenuVisibilityProvider, MenuRegistry, MenuAnnotation
- **Chat DTOs**: ChatDTO, ChatMessageDTO, DirectMessageDTO, ChatInvitationDTO, ChatMessagesPageDTO

## Dependencies
### External Dependencies
- None (pure interfaces and DTOs)

### Internal Module Dependencies
- None (base module)

## Configuration
No configuration required - this is a pure interface/DTO module.
