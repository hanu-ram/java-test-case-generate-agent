// NotificationService.java
package cloudhalo.tech.javatestcasegenerateagent.service;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    public boolean sendNotification(String userId, String message) {
        if (userId == null || message == null) {
            throw new IllegalArgumentException("User ID and message cannot be null");
        }
        System.out.println("sent notification to user " + userId + ": " + message);
        return true; 
    }
}