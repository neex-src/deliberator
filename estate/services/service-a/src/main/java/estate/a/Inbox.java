package estate.a;

import estate.common.SqsPoller;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Stores every inbox message in DynamoDB. The id is the message attribute "id" (else the SQS message id),
 * the correlation id the attribute "correlation" (else generated, see {@link estate.common.Correlation}).
 * A body that is not valid JSON is stored too, with status MALFORMED, so the record shows it arrived.
 * With {@code app.auto-publish=true} (APP_AUTO_PUBLISH, default false) a valid message is published to
 * the bus right away; otherwise POST /publish/{id} does it.
 */
@Component
public class Inbox extends SqsPoller {
  private static final Logger log = LoggerFactory.getLogger(Inbox.class);
  private final DynamoDbClient ddb;
  private final Publisher publisher;
  private final String table;
  private final boolean autoPublish;
  private final ObjectMapper json = new ObjectMapper();

  Inbox(SqsClient sqs, DynamoDbClient ddb, Publisher publisher, @Value("${app.inbox-queue}") String queue,
        @Value("${app.table}") String table, @Value("${app.auto-publish:false}") boolean autoPublish) {
    super(sqs, queue);
    this.ddb = ddb;
    this.publisher = publisher;
    this.table = table;
    this.autoPublish = autoPublish;
  }

  @Override
  protected void handle(Message m) throws Exception {
    var attr = m.messageAttributes().get("id");
    String id = attr != null ? attr.stringValue() : m.messageId();
    String corr = estate.common.Correlation.current();
    String body = m.body();
    boolean valid;
    try { json.readTree(body); valid = true; } catch (Exception e) { valid = false; }
    String status = valid ? "RECEIVED" : "MALFORMED";
    var item = new HashMap<String, AttributeValue>();
    item.put("id", AttributeValue.fromS(id));
    item.put("payload", AttributeValue.fromS(body));
    item.put("status", AttributeValue.fromS(status));
    item.put("correlation", AttributeValue.fromS(corr));
    item.put("bytes", AttributeValue.fromN(Integer.toString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
    item.put("receivedAt", AttributeValue.fromS(Instant.now().toString()));
    ddb.putItem(b -> b.tableName(table).item(item));
    log.info("stored message id={} status={} bytes={}", id, status, body.length());
    if (autoPublish && valid) publisher.publish(id, corr);
  }
}
