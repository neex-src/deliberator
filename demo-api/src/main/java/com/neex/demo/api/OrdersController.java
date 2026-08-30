package com.neex.demo.api;

import com.neex.demo.api.dto.CreateOrderRequest;
import com.neex.demo.api.dto.OrderResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** The implementation: no mappings, no documentation — you have to know OrdersApi to read it. */
@RestController
public class OrdersController implements OrdersApi {

    private static final Logger log = LoggerFactory.getLogger(OrdersController.class);

    private final OrderService service;

    public OrdersController(OrderService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<OrderResponse> create(@Valid CreateOrderRequest request, String trace) {
        OrderResponse created = service.create(request);
        log.info("created orderId={} customer={} amount={} items={} trace={}",
                created.orderId(), created.customer(), created.amount(), request.items() == null ? 0 : request.items().size(), trace);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create("/api/orders/" + created.orderId()));
        if (trace != null) response.header("X-Trace", trace);
        return response.body(created);
    }

    @Override
    public OrderResponse get(String id, boolean verbose) {
        OrderResponse order = service.get(id, verbose);
        log.info("get orderId={} verbose={} -> {}", id, verbose, order.status());
        return order;
    }
}
