package com.neex.demo.api;

import com.neex.demo.api.dto.CreateOrderRequest;
import com.neex.demo.api.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The contract, the way enterprise code writes it: every mapping and every line of OpenAPI
 * documentation lives here; the implementation shows none of it.
 */
@Tag(name = "orders", description = "Orders as seen by the shop front")
@RequestMapping("/api/orders")
public interface OrdersApi {

    @Operation(
            summary = "Create an order",
            description = "Stores the order with status NEW and returns it. Validation errors come back as 400 "
                    + "with one entry per field; a duplicate orderId is a 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
            @ApiResponse(responseCode = "409", description = "An order with this id already exists")
    })
    @PostMapping
    ResponseEntity<OrderResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The order to create", required = true)
            @RequestBody CreateOrderRequest request,
            @Parameter(description = "Caller-supplied trace id, echoed back in X-Trace")
            @RequestHeader(value = "X-Trace", required = false) String trace);

    @Operation(summary = "Get an order", description = "Reads one order by its id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such order")
    })
    @GetMapping("/{id}")
    OrderResponse get(
            @Parameter(description = "The order id", example = "ord-3f2a9c1e") @PathVariable("id") String id,
            @Parameter(description = "Include the order lines") @RequestParam(name = "verbose", defaultValue = "false") boolean verbose);
}
