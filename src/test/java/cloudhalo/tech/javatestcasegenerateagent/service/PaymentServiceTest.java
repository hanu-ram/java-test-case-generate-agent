package cloudhalo.tech.javatestcasegenerateagent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PaymentServiceTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private PaymentService paymentService;

    // Arrange
    @Test
    void should_complete_payment_when_valid_input() {
        String userId = "user123";
        BigDecimal amount = BigDecimal.valueOf(100);
        when(notificationService.sendNotification(userId, "Payment of " + amount + " processed.")).thenReturn(true);

        // Act
        String result = paymentService.processPayment(userId, amount);

        // Assert
        assertThat(result).isEqualTo("PAYMENT_COMPLETED");
    }

    // Arrange
    @Test
    void should_throw_exception_when_amount_is_null() {
        String userId = "user123";
        BigDecimal amount = null;

        // Act & Assert
        assertThatThrownBy(() -> paymentService.processPayment(userId, amount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Amount must be greater than zero");
    }

    // Arrange
    @Test
    void should_throw_exception_when_amount_is_zero_or_negative() {
        String userId = "user123";
        BigDecimal negativeAmount = BigDecimal.valueOf(-10);

        // Act & Assert
        assertThatThrownBy(() -> paymentService.processPayment(userId, negativeAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Amount must be greater than zero");

        BigDecimal zeroAmount = BigDecimal.ZERO;
        assertThatThrownBy(() -> paymentService.processPayment(userId, zeroAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Amount must be greater than zero");
    }

    // Arrange
    @Test
    void should_return_payment_success_notification_failed_when_notification_failed() {
        String userId = "user123";
        BigDecimal amount = BigDecimal.valueOf(100);
        when(notificationService.sendNotification(userId, "Payment of " + amount + " processed.")).thenReturn(false);

        // Act
        String result = paymentService.processPayment(userId, amount);

        // Assert
        assertThat(result).isEqualTo("PAYMENT_SUCCESS_NOTIFICATION_FAILED");
    }
}