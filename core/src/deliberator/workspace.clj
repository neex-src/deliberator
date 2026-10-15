(ns deliberator.workspace
  "Workspaces. Contract: `(load ws-name)`, `(save! ws)`, `(current-session ws)`,
  `(set-current-session! ws key)`. `workspace.edn` schema: `{:name :created :environments [names]
  :repos [...] :current-session key-or-nil}`."
  (:refer-clojure :exclude [load]))
