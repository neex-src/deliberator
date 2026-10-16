package estate.a;

import estate.common.Correlation;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * REST surface of service A. /ack needs the X-Api-Key header (the Secrets Manager secret). Every call
 * carries X-Correlation-Id (see {@link CorrelationFilter}); /publish uses the caller's id, else the
 * one stored with the message.
 */
@RestController
public class Api {
  private static final Logger log = LoggerFactory.getLogger(Api.class);
  private final Publisher publisher;
  private final String apiKey;

  Api(Publisher publisher, @Value("${app.api-key}") String apiKey) {
    this.publisher = publisher;
    this.apiKey = apiKey;
  }

  /** Puts the stored JSON payload on the bus as {"id":..., "correlation":..., "payload":...}. */
  @PostMapping("/publish/{id}")
  public Map<String, Object> publish(@PathVariable String id,
      @RequestHeader(value = Correlation.HEADER, required = false) String header) throws Exception {
    var stored = publisher.item(id).get("correlation");
    String corr = header != null && !header.isBlank() ? header
        : stored != null && stored.s() != null ? stored.s() : Correlation.current();
    try (var scope = Correlation.open(corr)) {
      return Map.of("id", id, "eventId", publisher.publish(id, corr), "correlation", corr);
    }
  }

  /** Called by service B: marks the message ACKED. */
  @PostMapping("/ack/{id}")
  public Map<String, String> ack(@PathVariable String id, @RequestHeader(value = "X-Api-Key", required = false) String key) {
    if (!apiKey.equals(key)) {
      log.warn("ack rejected id={} (bad api key)", id);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad api key");
    }
    try {
      publisher.setStatus(id, "ACKED", "ackedAt");
    } catch (ConditionalCheckFailedException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no message " + id);
    }
    log.info("ACK received id={}", id);
    return Map.of("id", id, "status", "ACKED");
  }

  @GetMapping("/messages/{id}")
  public Map<String, String> get(@PathVariable String id) {
    var out = new java.util.TreeMap<String, String>();
    publisher.item(id).forEach((k, v) -> { if (!k.equals("payload")) out.put(k, v.s() != null ? v.s() : v.n()); });
    return out;
  }
}
