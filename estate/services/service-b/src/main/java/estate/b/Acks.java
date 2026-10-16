package estate.b;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The ack ledger: one row of {@code acks} (estate/sql/init.sql) per ack service B sent to service A. */
@Component
public class Acks {
  private static final Logger log = LoggerFactory.getLogger(Acks.class);
  private final JdbcTemplate jdbc;

  Acks(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  void record(String messageId, String correlation, int status) {
    jdbc.update("insert into acks (message_id, correlation, a_status) values (?, ?, ?)", messageId, correlation, status);
    log.info("recorded ack in postgres id={} a_status={}", messageId, status);
  }
}
