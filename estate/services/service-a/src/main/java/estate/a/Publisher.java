package estate.a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/** Reads a stored message and puts it on the bus as {"id","correlation","payload"}; marks it PUBLISHED. */
@Component
public class Publisher {
  private static final Logger log = LoggerFactory.getLogger(Publisher.class);
  private final DynamoDbClient ddb;
  private final EventBridgeClient events;
  private final String table, bus;
  private final ObjectMapper json = new ObjectMapper();

  Publisher(DynamoDbClient ddb, EventBridgeClient events, @Value("${app.table}") String table,
            @Value("${app.bus}") String bus) {
    this.ddb = ddb;
    this.events = events;
    this.table = table;
    this.bus = bus;
  }

  Map<String, AttributeValue> item(String id) {
    var it = ddb.getItem(b -> b.tableName(table).key(Map.of("id", AttributeValue.fromS(id)))).item();
    if (it == null || it.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no message " + id);
    return it;
  }

  void setStatus(String id, String status, String tsField) {
    ddb.updateItem(b -> b.tableName(table).key(Map.of("id", AttributeValue.fromS(id)))
        .conditionExpression("attribute_exists(id)")
        .updateExpression("SET #s = :s, " + tsField + " = :t")
        .expressionAttributeNames(Map.of("#s", "status"))
        .expressionAttributeValues(Map.of(":s", AttributeValue.fromS(status),
            ":t", AttributeValue.fromS(Instant.now().toString()))));
  }

  /** @return the EventBridge event id; throws 422 for a MALFORMED payload and 502 when the bus refuses. */
  String publish(String id, String correlation) throws Exception {
    var it = item(id);
    if ("MALFORMED".equals(it.get("status").s()))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "payload of " + id + " is not valid JSON");
    ObjectNode detail = json.createObjectNode();
    detail.put("id", id);
    detail.put("correlation", correlation);
    detail.set("payload", json.readTree(it.get("payload").s()));
    String detailJson = json.writeValueAsString(detail);
    var res = events.putEvents(b -> b.entries(PutEventsRequestEntry.builder().eventBusName(bus)
        .source("estate.service-a").detailType("payload.published").detail(detailJson).build()));
    if (res.failedEntryCount() > 0) {
      var e = res.entries().get(0);
      log.error("publish failed id={} code={} message={}", id, e.errorCode(), e.errorMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.errorCode() + ": " + e.errorMessage());
    }
    setStatus(id, "PUBLISHED", "publishedAt");
    String eventId = res.entries().get(0).eventId();
    log.info("published id={} eventId={}", id, eventId);
    return eventId;
  }
}
