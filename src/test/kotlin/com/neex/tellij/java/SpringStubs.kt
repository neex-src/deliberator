package com.neex.tellij.java

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/** Just enough of Spring MVC, springdoc, Jackson and jakarta.validation for the resolver — no jars. */
object SpringStubs {
    private const val NONE = "\"\\n\\t\\t\\n\\t\\t\\n\\uE000\\uE001\\uE002\\n\\t\\t\\t\\t\\n\""

    fun addAll(f: JavaCodeInsightTestFixture) {
        val spring = "org.springframework.web.bind.annotation"
        f.addClass("package $spring; public enum RequestMethod { GET, POST, PUT, PATCH, DELETE }")
        f.addClass("package $spring; public @interface RequestMapping { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }")
        for (m in listOf("GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping"))
            f.addClass("package $spring; public @interface $m { String[] value() default {}; String[] path() default {}; }")
        f.addClass("package $spring; public @interface PathVariable { String value() default \"\"; String name() default \"\"; boolean required() default true; }")
        f.addClass("package $spring; public @interface RequestParam { String value() default \"\"; String name() default \"\"; boolean required() default true; String defaultValue() default $NONE; }")
        f.addClass("package $spring; public @interface RequestHeader { String value() default \"\"; String name() default \"\"; boolean required() default true; String defaultValue() default $NONE; }")
        f.addClass("package $spring; public @interface RequestBody { boolean required() default true; }")
        f.addClass("package $spring; public @interface ResponseStatus { org.springframework.http.HttpStatus value() default org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR; org.springframework.http.HttpStatus code() default org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR; }")
        f.addClass("package $spring; public @interface RestController { }")
        f.addClass("package org.springframework.http; public enum HttpStatus { OK, CREATED, ACCEPTED, NO_CONTENT, BAD_REQUEST, NOT_FOUND, CONFLICT, INTERNAL_SERVER_ERROR }")
        f.addClass("package org.springframework.http; public class ResponseEntity<T> { }")
        val oas = "io.swagger.v3.oas.annotations"
        f.addClass("package $oas; public @interface Operation { String summary() default \"\"; String description() default \"\"; }")
        f.addClass("package $oas; public @interface Parameter { String description() default \"\"; boolean required() default false; String example() default \"\"; }")
        f.addClass("package $oas.tags; public @interface Tag { String name(); String description() default \"\"; }")
        f.addClass("package $oas.media; public @interface Schema { Class<?> implementation() default Void.class; String description() default \"\"; String example() default \"\"; }")
        f.addClass("package $oas.media; public @interface Content { Schema schema() default @Schema; }")
        f.addClass("package $oas.responses; public @interface ApiResponse { String responseCode() default \"default\"; String description() default \"\"; $oas.media.Content[] content() default {}; }")
        f.addClass("package $oas.responses; public @interface ApiResponses { ApiResponse[] value() default {}; }")
        f.addClass("package $oas.parameters; public @interface RequestBody { String description() default \"\"; boolean required() default false; }")
        val v = "jakarta.validation.constraints"
        for (a in listOf("NotBlank", "NotNull", "NotEmpty", "Positive")) f.addClass("package $v; public @interface $a { }")
        f.addClass("package $v; public @interface Min { long value(); }")
        f.addClass("package $v; public @interface Size { int min() default 0; int max() default 2147483647; }")
        f.addClass("package jakarta.validation; public @interface Valid { }")
        f.addClass("package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }")
    }

    val ORDERS_API = """
        package com.acme.api;
        import org.springframework.web.bind.annotation.*;
        import org.springframework.http.ResponseEntity;
        import io.swagger.v3.oas.annotations.*;
        import io.swagger.v3.oas.annotations.media.*;
        import io.swagger.v3.oas.annotations.responses.*;
        import io.swagger.v3.oas.annotations.tags.Tag;
        @Tag(name = "orders", description = "Orders")
        @RequestMapping("/api/orders")
        public interface OrdersApi {
            @Operation(summary = "Create an order", description = "Stores the order with status NEW and returns it.")
            @ApiResponses({
                @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = OrderResponse.class))),
                @ApiResponse(responseCode = "400", description = "Validation failed")
            })
            @PostMapping
            ResponseEntity<OrderResponse> create(
                @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The order to create") @RequestBody CreateOrderRequest request,
                @Parameter(description = "Trace id") @RequestHeader(value = "X-Trace", required = false) String trace);
            @Operation(summary = "Get an order")
            @GetMapping("/{id}")
            OrderResponse get(@Parameter(description = "The order id") @PathVariable("id") String id,
                              @RequestParam(name = "verbose", defaultValue = "false") boolean verbose);
            @DeleteMapping("/{id}")
            void delete(@PathVariable String id);
            @RequestMapping(value = "/{id}/pay", method = RequestMethod.PUT)
            OrderResponse pay(@PathVariable String id, int amount);
        }
    """.trimIndent()

    val ORDERS_CONTROLLER = """
        package com.acme.api;
        import org.springframework.web.bind.annotation.*;
        import org.springframework.http.ResponseEntity;
        @RestController
        public class OrdersController implements OrdersApi {
            @Override public ResponseEntity<OrderResponse> create(@jakarta.validation.Valid CreateOrderRequest request, String trace) {
                int x = 1;
                return null;
            }
            @Override public OrderResponse get(String id, boolean verbose) { return null; }
            @Override public void delete(String id) { }
            @Override public OrderResponse pay(String id, int amount) { return null; }
            @PostMapping("/bulk")
            public OrderResponse bulk(@RequestBody CreateOrderRequest first) { return null; }
            public OrderResponse bulk(String raw) { return null; }
            public void helper() { }
        }
    """.trimIndent()

    val DTOS = listOf(
        "package com.acme.api; public enum Status { NEW, PAID }",
        """package com.acme.api;
           import jakarta.validation.constraints.*;
           public record Item(@NotBlank String sku, @Min(1) int qty) { }""",
        """package com.acme.api;
           import jakarta.validation.constraints.*;
           import io.swagger.v3.oas.annotations.media.Schema;
           public record CreateOrderRequest(
               @Schema(description = "Client-chosen id", example = "ord-1") @NotBlank String orderId,
               @NotBlank String customer,
               @NotNull @Positive java.math.BigDecimal amount,
               @jakarta.validation.Valid java.util.List<Item> items,
               @com.fasterxml.jackson.annotation.JsonProperty("gift_note") String giftNote) { }""",
        """package com.acme.api;
           public class OrderResponse {
               private String orderId; private Status status; private java.util.Map<String, Item> lines;
               public String getOrderId() { return orderId; }
               public Status getStatus() { return status; }
               public java.util.Map<String, Item> getLines() { return lines; }
               public OrderResponse getSelf() { return this; }
           }""",
    )

    fun project(f: JavaCodeInsightTestFixture) {
        addAll(f)
        DTOS.forEach { f.addClass(it) }
        f.addClass(ORDERS_API)
    }
}
