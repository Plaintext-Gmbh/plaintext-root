# Mandate Menu Whitelist/Blacklist Test Strategy

## Overview

This document outlines the comprehensive test strategy for the mandate-specific menu visibility feature with whitelist/blacklist functionality.

## Architecture

The mandate menu feature consists of three main components:

1. **MandateMenuConfig** (Entity) - Data model storing menu configurations
2. **MandateMenuVisibilityService** (Service) - Core business logic
3. **MandateMenuBackingBean** (Web UI) - User interface controller

## Test Pyramid

```
         /\
        /E2E\      ← Integration tests (database, full flow)
       /------\
      /Integr. \   ← Service integration tests
     /----------\
    /   Unit     \ ← Service & Bean unit tests (current focus)
   /--------------\
```

## Test Coverage

### 1. Unit Tests - MandateMenuVisibilityService

**File:** `MandateMenuVisibilityServiceTest.java`

#### Scenario 1: Whitelist Mode Active with Items
- **Case 1.1:** Show only whitelisted items
- **Case 1.2:** Show all items from whitelist set
- **Case 1.3:** Hide items not in whitelist

**Key Assertion:** When `whitelistMode=true`, only items in `hiddenMenus` are visible

#### Scenario 2: Blacklist Mode Active with Items
- **Case 2.1:** Show all except blacklisted items
- **Case 2.2:** Hide all items from blacklist set
- **Case 2.3:** Show items not in blacklist

**Key Assertion:** When `whitelistMode=false`, items NOT in `hiddenMenus` are visible

#### Scenario 3: Both Modes Configured
- **Case 3.1:** Whitelist takes precedence over blacklist

**Key Assertion:** Only whitelist mode is respected regardless of data semantics

#### Scenario 4: Neither Mode Active (Default)
- **Case 4.1:** Show all items when blacklist is empty
- **Case 4.2:** Treat null whitelistMode as false (blacklist mode)

**Key Assertion:** Default behavior is permissive (show all)

#### Scenario 5: Empty Whitelist
- **Case 5.1:** Hide all items when whitelist is empty
- **Case 5.2:** Defined behavior is: nothing is visible

**Key Assertion:** Empty whitelist = hide all menus (most restrictive)

#### Scenario 6: Empty Blacklist
- **Case 6.1:** Show all items when blacklist is empty

**Key Assertion:** Empty blacklist = show all menus (most permissive)

#### Scenario 7: Edge Cases - Null Values
- **Case 7.1:** Handle null mandate → show all
- **Case 7.2:** Handle empty mandate string → show all
- **Case 7.3:** Handle missing configuration → show all
- **Case 7.4:** Handle null hiddenMenus set → show all
- **Case 7.5:** Handle null menu title → no exception

**Key Assertion:** Null safety: missing data = permissive behavior

#### Scenario 8: Edge Cases - Concurrent Modifications
- **Case 8.1:** Handle modification of hiddenMenus while checking visibility
- **Case 8.2:** Handle rapid mode toggles

**Key Assertion:** Thread-safe or at least no unhandled exceptions

#### Scenario 9: Case Sensitivity
- **Case 9.1:** Handle case-insensitive mandate names
- **Case 9.2:** Preserve case sensitivity for menu titles (exact match required)

**Key Assertion:** Mandate names are normalized (lowercase); menu titles are case-sensitive

#### Scenario 10: Mode Transition - Toggle
- **Case 10.1:** Correctly invert menu selection when toggling

**Key Assertion:** Toggle inverts the selection set

#### Scenario 11: Configuration Persistence
- **Case 11.1:** Save whitelist configuration
- **Case 11.2:** Save blacklist configuration

**Key Assertion:** Config is saved with correct mode flag

### 2. Unit Tests - MandateMenuBackingBean

**File:** `MandateMenuBackingBeanTest.java`

#### Mode Toggle - Whitelist to Blacklist
- **Case B.1:** Toggle from blacklist to whitelist and invert selection
- **Case B.2:** Toggle from whitelist to blacklist and invert selection

**Key Assertion:** Toggle function correctly inverts the menu selection

#### Mode Toggle - Edge Cases
- **Case B.3:** Handle toggle with null selected mandate
- **Case B.4:** Handle toggle with empty available menus
- **Case B.5:** Handle toggle with null hidden menus
- **Case B.6:** Invert empty whitelist to all menus

**Key Assertion:** No exceptions on edge cases

#### Configuration Save - Preservation
- **Case B.7:** Save whitelist configuration correctly
- **Case B.8:** Save blacklist configuration correctly
- **Case B.9:** Preserve mode flag when saving

**Key Assertion:** Mode flag is persisted with configuration

#### Configuration Load and Initialize
- **Case B.10:** Correctly initialize detail page preserving mode
- **Case B.11:** Handle Hibernate lazy loading by creating new HashSet

**Key Assertion:** Lazy loading handled without Hibernate exceptions

#### Validation - Empty and Null Scenarios
- **Case B.12:** Reject save with null mandate name
- **Case B.13:** Reject save with empty mandate name
- **Case B.14:** Handle save with null selected mandate

**Key Assertion:** Validation prevents invalid data persistence

## Test Execution Strategy

### Prerequisites

1. **Maven Configuration**
   - JUnit 5 (Jupiter)
   - Mockito 4.x+
   - Spring Test Context

2. **Dependencies in pom.xml**
   ```xml
   <dependency>
       <groupId>org.junit.jupiter</groupId>
       <artifactId>junit-jupiter</artifactId>
       <scope>test</scope>
   </dependency>
   <dependency>
       <groupId>org.mockito</groupId>
       <artifactId>mockito-core</artifactId>
       <scope>test</scope>
   </dependency>
   <dependency>
       <groupId>org.mockito</groupId>
       <artifactId>mockito-junit-jupiter</artifactId>
       <scope>test</scope>
   </dependency>
   ```

### Running Tests

```bash
# Run all mandate menu tests
mvn test -Dtest=Mandate*

# Run specific test class
mvn test -Dtest=MandateMenuVisibilityServiceTest

# Run specific test method
mvn test -Dtest=MandateMenuVisibilityServiceTest#shouldShowOnlyWhitelistedItems

# Run with coverage
mvn test jacoco:report
```

## Test Coverage Targets

| Metric | Target | Current |
|--------|--------|---------|
| Line Coverage | >85% | - |
| Branch Coverage | >80% | - |
| Method Coverage | >90% | - |
| Classes Covered | 100% | - |

### Critical Paths to Cover

1. **Visibility Logic** (Core)
   - Whitelist logic path
   - Blacklist logic path
   - Mode precedence

2. **Null/Empty Handling** (Robustness)
   - Null mandate
   - Null menu title
   - Empty hiddenMenus set
   - Missing configuration

3. **Mode Toggle** (Feature)
   - Forward toggle (blacklist → whitelist)
   - Reverse toggle (whitelist → blacklist)
   - Selection inversion

4. **Persistence** (Data Integrity)
   - Save with correct mode
   - Load with correct mode
   - Transaction handling

## Integration Test Recommendations

### Future Test Cases (Not yet implemented)

#### Database Integration Tests
```java
@SpringBootTest
class MandateMenuConfigurationIntegrationTest {
    // Test actual database persistence
    // Test transaction handling
    // Test lazy loading with real Hibernate
}
```

#### End-to-End Tests
```java
@SpringBootTest
@WithWebClient
class MandateMenuWebIntegrationTest {
    // Test full UI flow: load → edit → save
    // Test mode toggle in browser
    // Test concurrent user access
}
```

## Test Quality Metrics

### Code Quality
- All tests use descriptive names (BDD-style)
- Each test focuses on one behavior
- Arrange-Act-Assert pattern used consistently
- Mock dependencies properly injected

### Assertion Quality
- Assertions are specific and meaningful
- Error messages explain what failed
- Both positive and negative cases covered
- Edge cases explicitly tested

### Maintainability
- Tests are independent (no shared state)
- Setup uses @BeforeEach to ensure clean state
- Nested test classes organize related tests
- Comments explain non-obvious test logic

## Known Issues & Limitations

### Current Implementation Assumptions

1. **Mandate Name Normalization**
   - Mandate names are normalized to lowercase for lookup
   - Menu titles must match exactly (case-sensitive)

2. **Null Handling**
   - Null whitelistMode is treated as false (blacklist)
   - Null mandate shows all menus (default behavior)
   - Missing configuration shows all menus

3. **Empty Set Behavior**
   - Empty whitelist = hide all (restrictive)
   - Empty blacklist = show all (permissive)

### Test Limitations

1. **No Database Tests Yet**
   - Current tests mock repository
   - Integration with actual database not tested
   - Transaction handling not verified

2. **No UI Tests Yet**
   - JSF/Faces interactions not tested
   - Page navigation not verified
   - Session scope behavior not tested

3. **No Concurrency Tests Yet**
   - Real concurrent modification not tested
   - Race conditions not explored
   - Lock mechanisms not verified

## Performance Considerations

### Operation Complexity

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| isMenuVisible | O(1) | HashMap lookup |
| Toggle | O(n) | Where n = number of menus |
| Save | O(n) | Collection population |
| Load | O(n) | Collection population |

### Performance Testing Recommendations

1. Test with large menu sets (1000+ items)
2. Test with concurrent access (multiple users)
3. Profile memory usage (especially hiddenMenus collection)

## Security Considerations

### Input Validation

1. **Menu Title Validation**
   - Should not contain SQL injection patterns
   - Should not contain XSS payloads

2. **Mandate Name Validation**
   - Should only contain alphanumeric and hyphens
   - Should have length limits

3. **Mode Toggle Validation**
   - Only authorized users should toggle mode
   - Toggle should not bypass audit trail

### Test Coverage for Security

- Null input handling
- Large input handling
- Special character handling

## Continuous Integration

### Maven Configuration

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>2.22.2</version>
    <configuration>
        <includes>
            <include>**/*Test.java</include>
            <include>**/*Tests.java</include>
        </includes>
    </configuration>
</plugin>
```

### CI Pipeline

1. **Build Phase**
   ```bash
   mvn clean compile
   ```

2. **Test Phase**
   ```bash
   mvn test
   ```

3. **Coverage Phase**
   ```bash
   mvn jacoco:report
   ```

4. **Verification**
   - Fail if coverage < 80%
   - Fail if tests fail
   - Generate test reports

## Documentation Standards

### Test Method Naming

Pattern: `should[ExpectedBehavior]When[Condition]`

Examples:
- `shouldShowOnlyWhitelistedItemsWhenWhitelistModeActive`
- `shouldHideAllItemsWhenWhitelistEmpty`
- `shouldHandleNullMandateGracefully`

### Test Documentation

- Use `@DisplayName` for human-readable names
- Add `@Nested` for test organization
- Include comments for complex assertions
- Document test purpose at class level

## Test Execution Timeline

### Phase 1: Initial Testing (Week 1)
- Run all unit tests
- Verify 100% pass rate
- Measure code coverage
- Document gaps

### Phase 2: Integration Testing (Week 2)
- Add database integration tests
- Add JSF/UI tests
- Verify persistence
- Test page flows

### Phase 3: Performance Testing (Week 3)
- Benchmark large datasets
- Stress test concurrent access
- Profile memory usage
- Document performance characteristics

### Phase 4: Security Testing (Week 4)
- Input validation testing
- Injection attack prevention
- Authorization enforcement
- Audit trail verification

## Success Criteria

1. **All tests pass** with zero failures
2. **Code coverage** exceeds 85%
3. **No critical defects** found in code review
4. **Performance** acceptable for production
5. **Documentation** complete and clear

## Future Enhancements

1. **Parameterized Tests**
   ```java
   @ParameterizedTest
   @ValueSource(strings = {"whitelist", "blacklist"})
   void testBothModes(String mode) { }
   ```

2. **Property-Based Testing**
   ```java
   @Property
   void visibilityPropertiesHold(@ForAll Set<String> menus) { }
   ```

3. **Mutation Testing**
   - Use PIT to verify test effectiveness
   - Ensure tests catch real bugs

4. **Contract Testing**
   - Test MenuVisibilityProvider interface contract
   - Verify implementations comply

## Reference Files

- **Service Implementation**: `MandateMenuVisibilityService.java`
- **Web Controller**: `MandateMenuBackingBean.java`
- **Entity Model**: `MandateMenuConfig.java`
- **Repository**: `MandateMenuConfigRepository.java`

## Contact & Questions

For questions about these tests or the mandate menu feature:
- Review implementation comments in source files
- Check CLAUDE.md for project conventions
- Contact plaintext.ch team

---

**Status**: Initial Test Strategy Defined
**Version**: 1.0.0
**Last Updated**: 2024-01-17
