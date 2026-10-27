package estate.common;

import java.security.Security;

/**
 * The JVM caches a successful name lookup for 30 s. A service of this estate finds its peers by container name on the runtime
 * network, and a container that is recreated (service.start, a new jar, a topology change) gets a new address: a caller that looked
 * the name up a moment before connects to the old address and times out until the entry expires (BUGS B24: the first order after
 * restoring service A stayed PUBLISHED, service B's POST to A's /ack failed with "Connect timed out", the queue redelivered it too late).
 * Call {@link #shortTtl()} first thing in {@code main}, before any lookup.
 */
public final class Dns {
  private Dns() {}

  public static void shortTtl() {
    Security.setProperty("networkaddress.cache.ttl", "2");
    Security.setProperty("networkaddress.cache.negative.ttl", "1");
  }
}
