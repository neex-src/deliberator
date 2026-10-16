package estate.a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Service A: SQS inbox -> DynamoDB, POST /publish/{id} -> EventBridge, POST /ack/{id} <- service B. */
@SpringBootApplication
public class ServiceA {
  public static void main(String[] args) {
    SpringApplication.run(ServiceA.class, args);
  }

  @Bean SqsClient sqs() { return SqsClient.create(); }
  @Bean DynamoDbClient dynamo() { return DynamoDbClient.create(); }
  @Bean EventBridgeClient events() { return EventBridgeClient.create(); }
}
