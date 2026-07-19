package com.petmarketplace.infrastructure.notification;

import com.petmarketplace.domain.user.entity.User;
import java.util.Map;

/**
 * Contract for push notifications. FCM integration is intentionally left as a future task;
 * the current implementation is a no-op stub.
 */
public interface PushNotificationService {

    void sendNotification(User user, String title, String body);

    void sendNotification(User user, String title, String body, Map<String, String> data);
}
