// PaymentService.java
package cloudhalo.tech.javatestcasegenerateagent.service;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
public class PaymentService {

    private final NotificationService notificationService;

    public PaymentService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public String processPayment(String userId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        boolean notified = notificationService.sendNotification(userId, "Payment of " + amount + " processed.");
        
        if (!notified) {
            return "PAYMENT_SUCCESS_NOTIFICATION_FAILED";
        }

        return "PAYMENT_COMPLETED";
    }
}