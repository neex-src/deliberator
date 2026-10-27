package estate.b;

import estate.common.Dns;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Service B: EventBridge -> SQS -> POST service A /ack/{id}. */
@SpringBootApplication
public class ServiceB {
  public static void main(String[] args) {
    Dns.shortTtl();   // B24: a recreated peer has a new address; do not keep the old one for 30 s
    SpringApplication.run(ServiceB.class, args);
  }

  @Bean SqsClient sqs() { return SqsClient.create(); }
}
