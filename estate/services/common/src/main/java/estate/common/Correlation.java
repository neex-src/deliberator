package estate.common;

import java.util.UUID;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The correlation id: HTTP header {@value #HEADER}, SQS message attribute {@value #KEY}, event field
 * {@code detail.correlation}. While a scope is open it is in the MDC under {@value #KEY}, so every log
 * line carries it (pattern {@code %X{correlation}}).
 */
public final class Correlation {
  public static final String KEY = "correlation";
  public static final String HEADER = "X-Correlation-Id";

  private Correlation() { }

  public static String create() { return UUID.randomUUID().toString(); }

  /** The current id, or null outside a scope. */
  public static String current() { return MDC.get(KEY); }

  /** The id of a queue message: its {@code correlation} attribute, else a fresh one. */
  public static String of(Message m) {
    var a = m.messageAttributes().get(KEY);
    return a != null && a.stringValue() != null && !a.stringValue().isBlank() ? a.stringValue() : create();
  }

  /** Puts {@code id} (or a fresh id when blank) in the MDC until the scope is closed. */
  public static Scope open(String id) {
    String use = id == null || id.isBlank() ? create() : id;
    String previous = MDC.get(KEY);
    MDC.put(KEY, use);
    return new Scope(use, previous);
  }

  public static final class Scope implements AutoCloseable {
    public final String id;
    private final String previous;

    private Scope(String id, String previous) { this.id = id; this.previous = previous; }

    @Override
    public void close() {
      if (previous == null) MDC.remove(KEY); else MDC.put(KEY, previous);
    }
  }
}
