package estate.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-polls one SQS queue (looked up by name, retried until it exists) on its own thread and hands each
 * message to {@link #handle} inside a {@link Correlation} scope (the message's {@code correlation} attribute). A message is deleted only after handle returns; if it throws, the message
 * reappears after the visibility timeout (the queue's redrive policy, if any, takes over).
 */
public abstract class SqsPoller implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(SqsPoller.class);
  private final SqsClient sqs;
  private final String queueName;
  private volatile boolean running;
  private Thread thread;

  protected SqsPoller(SqsClient sqs, String queueName) {
    this.sqs = sqs;
    this.queueName = queueName;
  }

  protected abstract void handle(Message message) throws Exception;

  @Override
  public void start() {
    running = true;
    thread = new Thread(this::loop, "sqs-" + queueName);
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public void stop() {
    running = false;
    if (thread != null) thread.interrupt();
  }

  @Override
  public boolean isRunning() { return running; }

  private void loop() {
    String url = null;
    while (running && url == null) {
      try {
        url = sqs.getQueueUrl(b -> b.queueName(queueName)).queueUrl();
      } catch (Exception e) {
        log.warn("queue {} not available yet: {}", queueName, e.toString());
        sleep(3000);
      }
    }
    log.info("listening on queue {} ({})", queueName, url);
    final String queueUrl = url;
    while (running) {
      try {
        var res = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
            .maxNumberOfMessages(10).waitTimeSeconds(5).messageAttributeNames("All")
            .messageSystemAttributeNames(MessageSystemAttributeName.ALL).build());
        for (Message m : res.messages()) {
          try (var scope = Correlation.open(Correlation.of(m))) {
            try {
              handle(m);
              sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
            } catch (Exception e) {
              log.error("handling message {} failed, it will be redelivered", m.messageId(), e);
            }
          }
        }
      } catch (Exception e) {
        if (running) { log.error("receive on {} failed: {}", queueName, e.toString()); sleep(2000); }
      }
    }
  }

  private static void sleep(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
  }
}
