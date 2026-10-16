package estate.b;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Service B: EventBridge -> SQS -> POST service A /ack/{id}. */
@SpringBootApplication
public class ServiceB {
  public static void main(String[] args) {
    SpringApplication.run(ServiceB.class, args);
  }

  @Bean SqsClient sqs() { return SqsClient.create(); }
}
