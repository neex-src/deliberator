package estate.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException;

/**
 * Logback appender that ships encoded events to a CloudWatch Logs group (one stream per process start).
 * Does nothing when {@code group} is blank. A CloudWatch failure is reported on stderr and never reaches
 * the application. Region, endpoint and credentials come from the AWS SDK defaults.
 */
public class CloudWatchLogsAppender extends AppenderBase<ILoggingEvent> {
  private static final int MAX_MESSAGE_CHARS = 200_000;
  private Encoder<ILoggingEvent> encoder;
  private String group;
  private String stream;
  private CloudWatchLogsClient client;
  private final BlockingQueue<InputLogEvent> queue = new LinkedBlockingQueue<>(20_000);
  private volatile boolean running;
  private Thread flusher;

  public void setEncoder(Encoder<ILoggingEvent> encoder) { this.encoder = encoder; }
  public void setGroup(String group) { this.group = group; }

  @Override
  public void start() {
    if (group == null || group.isBlank() || encoder == null) {
      addWarn("CloudWatch logging disabled (no group)");
      return;
    }
    try {
      if (!encoder.isStarted()) encoder.start();
      client = CloudWatchLogsClient.create();
      stream = host() + "-" + System.currentTimeMillis();
      try { client.createLogGroup(b -> b.logGroupName(group)); } catch (ResourceAlreadyExistsException ignored) { }
      client.createLogStream(b -> b.logGroupName(group).logStreamName(stream));
    } catch (Exception e) {
      System.err.println("estate: CloudWatch logging disabled: " + e);
      return;
    }
    running = true;
    flusher = new Thread(this::loop, "cloudwatch-flusher");
    flusher.setDaemon(true);
    flusher.start();
    super.start();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (!running) return;
    String msg = new String(encoder.encode(event), StandardCharsets.UTF_8).strip();
    if (msg.length() > MAX_MESSAGE_CHARS) msg = msg.substring(0, MAX_MESSAGE_CHARS);
    queue.offer(InputLogEvent.builder().timestamp(event.getTimeStamp()).message(msg).build());
  }

  private void loop() {
    while (running || !queue.isEmpty()) {
      try {
        InputLogEvent first = queue.poll(1, TimeUnit.SECONDS);
        if (first == null) { if (!running) break; continue; }
        List<InputLogEvent> batch = new ArrayList<>();
        batch.add(first);
        queue.drainTo(batch, 200);
        batch.sort(Comparator.comparingLong(InputLogEvent::timestamp));
        client.putLogEvents(b -> b.logGroupName(group).logStreamName(stream).logEvents(batch));
      } catch (InterruptedException e) {
        return;
      } catch (Exception e) {
        System.err.println("estate: CloudWatch put failed: " + e);
      }
    }
  }

  @Override
  public void stop() {
    running = false;
    if (flusher != null) {
      try { flusher.join(5000); } catch (InterruptedException ignored) { }
    }
    super.stop();
  }

  private static String host() {
    try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "host"; }
  }
}
