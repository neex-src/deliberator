(ns deliberator.podman
  "Podman REST client over `unix://` (JDK `UnixDomainSocketAddress` plus hand-written HTTP/1.1
  framing, chunked responses supported) and `tcp://` (`java.net.http`). Operations: `info`,
  `networks` (`ensure-network!`), `images` (`pull!` streaming progress events), `containers`
  (`list`, `inspect`, `create!`, `start!`, `stop!`, `remove!`, `logs` as a lazy seq of
  `{:stream :stdout|:stderr :at :text}` with `since`/`follow`, `exec!`). Every response body goes
  through the raw store (`:source :podman`) except `logs` streams, which land in
  `logs/services/<name>/<date>.log` as text.")
