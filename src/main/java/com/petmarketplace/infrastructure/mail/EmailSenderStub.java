package com.petmarketplace.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "mail", name = "enabled", havingValue = "false", matchIfMissing = true)
public class EmailSenderStub implements EmailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[EMAIL STUB] To: {}, Subject: {}, Body: {}", to, subject, body);
    }

    @Override
    public void sendHtml(String to, String subject, String htmlBody) {
        log.info("[EMAIL STUB] To: {}, Subject: {}, HTML body length: {}", to, subject, htmlBody.length());
    }
}
