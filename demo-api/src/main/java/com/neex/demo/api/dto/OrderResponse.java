package com.neex.demo.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "An order as the service knows it")
public record OrderResponse(
        String orderId,
        String customer,
        BigDecimal amount,
        @Schema(description = "NEW until a payment arrives", allowableValues = {"NEW", "PAID"}) String status,
        @Schema(description = "Which channel created it: api, kafka or workbench") String source,
        Instant receivedAt,
        @Schema(description = "Only with ?verbose=true") List<Item> items) {
}
