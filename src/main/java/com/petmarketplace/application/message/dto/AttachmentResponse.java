package com.petmarketplace.application.message.dto;

import java.io.Serializable;
import java.util.UUID;

public record AttachmentResponse(
        UUID messageId,
        String url,
        String filename,
        String contentType,
        long size
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
