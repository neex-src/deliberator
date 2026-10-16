package estate.b;

import estate.common.Correlation;
import estate.common.SqsPoller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Reads the EventBridge envelope that the rule delivers to B's queue, takes detail.id and
 * detail.correlation, tells service A "got it" via POST /ack/{id} (shared api key, X-Correlation-Id)
 * and records the ack in Postgres. Throwing keeps the message on the queue for retry.
 */
@Component
public class Events extends SqsPoller {
  private static final Logger log = LoggerFactory.getLogger(Events.class);
  private final RestClient http;
  private final String apiKey;
  private final Acks acks;
  private final ObjectMapper json = new ObjectMapper();

  Events(SqsClient sqs, Acks acks, @Value("${app.events-queue}") String queue, @Value("${app.service-a-url}") String aUrl,
         @Value("${app.api-key}") String apiKey) {
    super(sqs, queue);
    this.apiKey = apiKey;
    this.acks = acks;
    var rf = new SimpleClientHttpRequestFactory();
    rf.setConnectTimeout(5000);
    rf.setReadTimeout(15000);
    this.http = RestClient.builder().baseUrl(aUrl).requestFactory(rf).build();
  }

  @Override
  protected void handle(Message m) throws Exception {
    var env = json.readTree(m.body());
    String id = env.path("detail").path("id").asText(null);
    try (var scope = Correlation.open(env.path("detail").path("correlation").asText(null))) {
      if (id == null) {
        log.warn("event without detail.id, dropped: {}", m.messageId());
        return;
      }
      log.info("received event id={} source={}", id, env.path("source").asText());
      var res = http.post().uri("/ack/{id}", id).header("X-Api-Key", apiKey)
          .header(Correlation.HEADER, scope.id).retrieve().toBodilessEntity();
      log.info("acked to A id={} status={}", id, res.getStatusCode().value());
      acks.record(id, scope.id, res.getStatusCode().value());
    }
  }
}
