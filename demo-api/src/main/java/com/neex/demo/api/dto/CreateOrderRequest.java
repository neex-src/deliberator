package com.neex.demo.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "What a client sends to create an order")
public record CreateOrderRequest(
        @Schema(description = "Client-chosen order id", example = "ord-3f2a9c1e") @NotBlank String orderId,
        @Schema(description = "Customer name", example = "alice") @NotBlank String customer,
        @Schema(description = "Total amount", example = "42.5") @NotNull @Positive BigDecimal amount,
        @Schema(description = "Order lines") @Valid List<Item> items) {
}
