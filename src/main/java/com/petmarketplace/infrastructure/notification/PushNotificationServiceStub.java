package com.petmarketplace.infrastructure.notification;

import com.petmarketplace.domain.user.entity.User;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PushNotificationServiceStub implements PushNotificationService {

    @Override
    public void sendNotification(User user, String title, String body) {
        sendNotification(user, title, body, Map.of());
    }

    @Override
    public void sendNotification(User user, String title, String body, Map<String, String> data) {
        log.info("[PUSH STUB] To user {}: {} - {} (data: {})",
                user != null ? user.getId() : null, title, body, data);
    }
}
