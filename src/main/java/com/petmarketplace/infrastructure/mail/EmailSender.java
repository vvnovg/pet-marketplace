package com.petmarketplace.infrastructure.mail;

public interface EmailSender {

    void send(String to, String subject, String body);

    void sendHtml(String to, String subject, String htmlBody);
}
