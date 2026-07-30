# Mandate Menu Test Execution Guide

## Quick Start

### Prerequisites

1. JDK 17+ installed
2. Maven 3.8+ installed
3. Project root accessible

### Run All Tests

```bash
cd /Users/mad/code/plaintext-boot
mvn clean test -Dtest=MandateMenu*
```

### Run Specific Test Class

```bash
# Service tests only
mvn test -Dtest=MandateMenuVisibilityServiceTest

# Backing bean tests only
mvn test -Dtest=MandateMenuBackingBeanTest
```

## Test Structure Overview

```
plaintext-root-menuesteuerung/
├── src/
│   ├── main/
│   │   └── java/ch/plaintext/menuesteuerung/
│   │       ├── model/
│   │       │   └── MandateMenuConfig.java
│   │       ├── service/
│   │       │   └── MandateMenuVisibilityService.java
│   │       ├── web/
│   │       │   └── MandateMenuBackingBean.java
│   │       └── persistence/
│   │           └── MandateMenuConfigRepository.java
│   └── test/
│       └── java/ch/plaintext/menuesteuerung/
│           ├── service/
│           │   └── MandateMenuVisibilityServiceTest.java
│           └── web/
│               └── MandateMenuBackingBeanTest.java
├── TEST_STRATEGY.md (this guide)
└── pom.xml
```

## Test Scenarios Summary

### MandateMenuVisibilityServiceTest (43 test cases)

| Scenario | Test Cases | Focus |
|----------|-----------|-------|
| Whitelist Active | 3 | Show only whitelisted items |
| Blacklist Active | 3 | Show all except blacklisted |
| Both Modes | 1 | Whitelist precedence |
| Neither Active | 2 | Default behavior |
| Empty Whitelist | 2 | Hide all behavior |
| Empty Blacklist | 1 | Show all behavior |
| Null Values | 5 | Null safety |
| Concurrent Mods | 2 | Thread safety |
| Case Sensitivity | 2 | Name normalization |
| Mode Transition | 1 | Toggle inversion |
| Persistence | 2 | Save config |

### MandateMenuBackingBeanTest (14 test cases)

| Scenario | Test Cases | Focus |
|----------|-----------|-------|
| Toggle White→Black | 2 | Mode switching |
| Toggle Edge Cases | 4 | Error handling |
| Save & Preserve | 3 | Configuration persistence |
| Load & Initialize | 2 | Lazy loading |
| Validation | 3 | Input validation |

## Running Tests with Different Configurations

### Run Tests with Debug Output

```bash
mvn test -Dtest=MandateMenuVisibilityServiceTest -X
```

### Run Tests with Code Coverage

```bash
cd /Users/mad/code/plaintext-boot
mvn clean test jacoco:report

# View coverage report
open plaintext-root-menuesteuerung/target/site/jacoco/index.html
```

### Run Tests Matching Pattern

```bash
# All whitelist tests
mvn test -Dtest=MandateMenuVisibilityServiceTest -Dgroups="Whitelist*"

# All edge case tests
mvn test -Dtest=MandateMenuVisibilityServiceTest -Dgroups="EdgeCases*"
```

## Test Output Example

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running ch.plaintext.menuesteuerung.service.MandateMenuVisibilityServiceTest
[INFO] MandateMenuVisibilityService - Whitelist/Blacklist Tests
[INFO]
[INFO]   Scenario 1: Whitelist Active with Items
[INFO]     Should show only whitelisted items ... PASSED (12ms)
[INFO]     Should show all items from whitelist set ... PASSED (8ms)
[INFO]     Should hide items not in whitelist ... PASSED (10ms)
[INFO]
[INFO]   Scenario 2: Blacklist Active with Items
[INFO]     Should show all except blacklisted items ... PASSED (9ms)
[INFO]     Should hide all items from blacklist set ... PASSED (11ms)
[INFO]     Should show items not in blacklist ... PASSED (8ms)
[INFO]
[INFO] Running ch.plaintext.menuesteuerung.web.MandateMenuBackingBeanTest
[INFO] MandateMenuBackingBean - Whitelist/Blacklist Tests
[INFO]
[INFO]   Mode Toggle - Whitelist to Blacklist
[INFO]     Should toggle from blacklist to whitelist ... PASSED (7ms)
[INFO]     Should toggle from whitelist to blacklist ... PASSED (6ms)
[INFO]
[INFO] -------------------------------------------------------
[INFO] Tests run: 57, Failures: 0, Errors: 0, Skipped: 0
[INFO] -------------------------------------------------------
[INFO] BUILD SUCCESS
```

## Understanding Test Results

### Passing Tests (Green)
- Logic works as expected
- No exceptions thrown
- All assertions passed

### Failing Tests (Red)
- Logic doesn't match expectation
- Exception was thrown
- Assertion failed

Example failure output:
```
AssertionError: Expected true but was false
  Menu 'Menu1' should be visible in whitelist mode
  at MandateMenuVisibilityServiceTest.shouldShowOnlyWhitelistedItems(...)
```

### Skipped Tests (Yellow)
- Test marked with @Disabled
- Conditional skip logic triggered
- Dependency not available

## Test Case Breakdown

### Test Case: Whitelist Active - Show Only Whitelisted

```
GIVEN: Whitelist mode enabled with items [Menu1, Menu2, Menu3]
  AND: Config is retrieved from repository
WHEN: Checking visibility of "Menu1"
THEN: Return true (item is in whitelist)
  AND: Checking visibility of "Menu99"
THEN: Return false (item not in whitelist)
```

**Test Code Location**: `MandateMenuVisibilityServiceTest.java` Line ~70
**Expected Result**: PASS
**Execution Time**: <50ms

### Test Case: Empty Whitelist

```
GIVEN: Whitelist mode enabled with EMPTY list []
WHEN: Checking visibility of any menu
THEN: Return false for all menus
```

**Test Code Location**: `MandateMenuVisibilityServiceTest.java` Line ~160
**Expected Result**: PASS
**Execution Time**: <50ms

### Test Case: Toggle Blacklist to Whitelist

```
GIVEN: Blacklist mode with hidden menus [Menu1, Menu2]
  AND: All available menus [Menu1, Menu2, Menu3, Menu4, Menu5]
WHEN: Toggle mode is called
THEN: Switch to whitelist mode
  AND: Whitelist becomes [Menu3, Menu4, Menu5] (previously visible items)
```

**Test Code Location**: `MandateMenuBackingBeanTest.java` Line ~53
**Expected Result**: PASS
**Execution Time**: <50ms

## Continuous Integration Setup

### GitHub Actions Example (`.github/workflows/test-mandate-menu.yml`)

```yaml
name: Mandate Menu Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'adopt'

      - name: Run Mandate Menu Tests
        run: |
          mvn clean test -Dtest=MandateMenu* \
                        -Dorg.slf4j.simpleLogger.defaultLogLevel=info

      - name: Generate Coverage Report
        run: mvn jacoco:report

      - name: Upload Coverage
        uses: codecov/codecov-action@v3
```

## Troubleshooting

### Test Fails: "No Such Bean as MandateMenuVisibilityService"

**Cause**: Service not properly mocked
**Solution**: Verify @Mock annotation and @InjectMocks annotation are present

```java
@Mock
private MandateMenuVisibilityService service;

@InjectMocks
private MandateMenuBackingBean backingBean;
```

### Test Fails: "NullPointerException in isMenuVisible"

**Cause**: Null mandate or config not mocked
**Solution**: Add explicit null check test case or verify mock setup

```java
when(repository.findByMandateName(null)).thenReturn(Optional.empty());
```

### Tests Run Slowly (>1000ms total)

**Cause**: Too many object creations or I/O operations
**Solution**: Verify mocks are used and no actual database calls

```java
verify(repository, never()).save(any()); // Ensure no actual saves
```

### Test Fails: "Expected true, but was false" on Whitelist Test

**Cause**: Logic inverted - treating whitelist as blacklist
**Solution**: Verify whitelistMode is set correctly

```java
@BeforeEach
void setup() {
    testConfig.setWhitelistMode(true);  // Must be true for whitelist
    // NOT: testConfig.setWhitelistMode(false);
}
```

## Performance Benchmarks

### Expected Execution Times

| Operation | Time | Notes |
|-----------|------|-------|
| Single visibility check | <1ms | HashMap lookup |
| Whitelist scenario (3 tests) | <30ms | No I/O |
| Blacklist scenario (3 tests) | <25ms | No I/O |
| Toggle scenario (2 tests) | <20ms | Set manipulation |
| All 57 tests | <500ms | Total run time |

### Measuring Performance

```bash
# Run with timing
mvn test -Dtest=MandateMenuVisibilityServiceTest \
         -Dorg.slf4j.simpleLogger.showDateTime=true

# Or check test report
cat target/surefire-reports/*.txt | grep "Tests run"
```

## Creating Additional Tests

### Test Template

```java
@Test
@DisplayName("Should [expected behavior] when [condition]")
void shouldDescribeExpectedBehavior() {
    // ARRANGE - Set up test data
    testConfig.setWhitelistMode(true);
    testConfig.setHiddenMenus(Set.of("Menu1"));
    when(repository.findByMandateName("mandate")).thenReturn(Optional.of(testConfig));

    // ACT - Execute the method being tested
    boolean result = service.isMenuVisibleForMandate("Menu1", "mandate");

    // ASSERT - Verify the result
    assertTrue(result, "Menu1 should be visible");
}
```

### Adding to Existing Scenario

Add new test method within appropriate @Nested class:

```java
@Nested
@DisplayName("Scenario X: Your New Scenario")
class YourNewScenario {

    @BeforeEach
    void setup() {
        // Setup specific to this scenario
    }

    @Test
    @DisplayName("Should [behavior]")
    void shouldTestBehavior() {
        // Test implementation
    }
}
```

## Code Coverage Analysis

### Coverage Report Location

After running `mvn jacoco:report`:
```
plaintext-root-menuesteuerung/target/site/jacoco/
```

### Coverage by Class

- **MandateMenuConfig**: 100% expected
- **MandateMenuVisibilityService**: 95%+ target
- **MandateMenuBackingBean**: 85%+ target

### Identifying Coverage Gaps

```bash
# Run tests with coverage
mvn clean test jacoco:report

# Check specific class coverage
grep -A 20 "MandateMenuVisibilityService" target/site/jacoco/index.html
```

## Test Maintenance

### When to Update Tests

1. **Feature Changes**: New functionality needs new tests
2. **Bug Fixes**: Add test case that reproduces bug
3. **Refactoring**: Ensure tests still pass with new code structure
4. **Performance**: Add performance benchmarks if optimizing

### Test Review Checklist

- [ ] Test has descriptive name (BDD-style)
- [ ] Test focuses on one behavior
- [ ] Arrange-Act-Assert pattern followed
- [ ] Mocks properly configured
- [ ] No hardcoded test data
- [ ] Handles edge cases
- [ ] Comments explain complex logic

## Additional Resources

### Test Documentation
- `TEST_STRATEGY.md` - Detailed test strategy
- Source code comments - Implementation details
- Javadoc - API documentation

### Related Files
- `MandateMenuConfig.java` - Entity model
- `MandateMenuVisibilityService.java` - Service logic
- `MandateMenuBackingBean.java` - Web UI controller

### External References
- JUnit 5 Documentation: https://junit.org/junit5/docs/current/user-guide/
- Mockito Documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html

---

**Last Updated**: 2024-01-17
**Test Version**: 1.0.0
**Status**: Ready for Execution
