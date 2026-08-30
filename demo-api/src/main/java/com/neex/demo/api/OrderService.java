package com.neex.demo.api;

import com.neex.demo.api.dto.CreateOrderRequest;
import com.neex.demo.api.dto.Item;
import com.neex.demo.api.dto.OrderResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** Rows in the demo Postgres (same table the Kafka consumer fills), order lines kept in memory. */
@Service
public class OrderService {

    private final JdbcTemplate jdbc;
    private final Map<String, List<Item>> items = new ConcurrentHashMap<>();

    public OrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OrderResponse create(CreateOrderRequest request) {
        try {
            jdbc.update("insert into orders (id, customer, amount, source, status) values (?, ?, ?, 'api', 'NEW')",
                    request.orderId(), request.customer(), request.amount());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("order " + request.orderId() + " already exists");
        }
        if (request.items() != null) items.put(request.orderId(), List.copyOf(request.items()));
        return get(request.orderId(), true);
    }

    public OrderResponse get(String id, boolean verbose) {
        List<OrderResponse> rows = jdbc.query(
                "select id, customer, amount, status, source, received_at from orders where id = ?",
                (rs, i) -> new OrderResponse(
                        rs.getString("id"), rs.getString("customer"), rs.getBigDecimal("amount"),
                        rs.getString("status"), rs.getString("source"), rs.getTimestamp("received_at").toInstant(),
                        verbose ? items.getOrDefault(rs.getString("id"), List.of()) : null),
                id);
        if (rows.isEmpty()) throw new NoSuchElementException("no order " + id);
        return rows.get(0);
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
