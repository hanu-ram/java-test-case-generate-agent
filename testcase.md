## Test Plan for EmailService

This plan outlines the unit tests for the `EmailService` class.

### Dependencies
- `JavaMailSender`: To be mocked using Mockito.
- `Logger`: To be mocked using Mockito.

### Test Cases

1.  **`should_sendEmailSuccessfully_when_validInputIsProvided`**:
    - **Given**: Valid `to`, `subject`, and `text` strings.
    - **When**: `sendEmail` is called.
    - **Then**:
        - Verify that `javaMailSender.send(SimpleMailMessage)` is called exactly once.
        - Verify that the `SimpleMailMessage` passed to the sender contains the correct `to`, `subject`, and `text`.
        - Verify that a success message is logged.

2.  **`should_logError_when_mailSenderThrowsException`**:
    - **Given**: Valid `to`, `subject`, and `text` strings.
    - **And**: The mocked `javaMailSender` is configured to throw a `MailException` when `send` is called.
    - **When**: `sendEmail` is called.
    - **Then**:
        - Verify that `javaMailSender.send()` is called.
        - Verify that an error message is logged containing the exception details.

3.  **`should_callMailSender_when_toIsNull`**:
    - **Given**: A null `to` address.
    - **When**: `sendEmail` is called.
    - **Then**:
        - Verify that `javaMailSender.send(SimpleMailMessage)` is called.
        - Verify the message's `to` field is null.

4.  **`should_callMailSender_when_subjectIsNull`**:
    - **Given**: A null `subject`.
    - **When**: `sendEmail` is called.
    - **Then**:
        - Verify that `javaMailSender.send(SimpleMailMessage)` is called.
        - Verify the message's `subject` field is null.

5.  **`should_callMailSender_when_textIsNull`**:
    - **Given**: A null `text` body.
    - **When**: `sendEmail` is called.
    - **Then**:
        - Verify that `javaMailSender.send(SimpleMailMessage)` is called.
        - Verify the message's `text` field is null.
