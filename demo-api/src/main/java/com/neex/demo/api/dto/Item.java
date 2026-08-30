package com.neex.demo.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "One line of an order")
public record Item(
        @Schema(description = "Stock keeping unit", example = "A-1") @NotBlank String sku,
        @Schema(description = "Quantity, at least one", example = "2") @Min(1) int qty) {
}
