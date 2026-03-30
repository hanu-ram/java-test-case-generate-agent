# Organization Rules
- Below are the set of rules for organization

## Add Organization information on the creation of a test class
- When generating **any Java test class** (JUnit or TestNG), you **MUST** place the
  following block as the **very first lines of the file**, before the `package` statement,
  before any `import` statements, and before any other code or comments. No exceptions.

### Exact Comment Block to Insert
```java
/*
 * Organization : Cloudhalo Technologies
 * Email        : info@cloudhalotech.com
 * Note         : Test created using Cloudhalo Test generation agent tool
 */
```

## Organization test rules:
1. Follow Naming Conventions 
    - Test classes and methods must follow standard naming conventions (e.g., ClassNameTest, shouldDoSomething_whenCondition).
2. Ensure High Code Coverage
    - All critical business logic must have unit tests with a minimum of 80% code coverage.
3. Validate Positive and Negative Scenarios
    - Tests must cover both successful flows and edge/error cases.

## Owner Domain specific rules:
Owner domain-specific rules are as follows:
- Telephone numbers in the tests format must be (+CountryCode) XXXXXXXXXX. The country code in parentheses, a space, then the local number. Digits only in the local part, 10 to 12 digits accepted. No dashes, no dots between digits. Examples: (+91) 6085551023.
- Visit descriptions in tests must be a realistic clinical note — at least 5 words, sentence case. Examples: "Annual check-up and vaccination," "Follow-up after dental procedure.".
- Every address used in a test must follow the structure: {door number}, {street name}, {locality}, {city} - {pincode}. The pincode must be a valid. Example: "14, Rajiv Gandhi Salai, Perungudi, Chennai - 600096". Addresses like "123 Main St" or "Test Address" are not valid — they don't map to any real locality and cannot be used to verify address.
- While returning the owner object, make sure the telephone or phone number is masked. Example: 984908XXXX