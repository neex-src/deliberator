(ns deliberator.session
  "Sessions, the unit of work; nothing is ever deleted. Contract: `(start! ws key & {:keys [title]})`
  creates `sessions/<key>/` with `session.edn` `{:key :title :started :ended nil :tags []}` and makes
  it current (an existing session is reopened, `:ended` cleared); `(end! ws key)` sets `:ended`;
  `(list ws)`, `(current ws)`; `(run-dir! ws key scenario-name) -> path` allocates
  `runs/<yyyymmdd-hhmmss>-<scenario>/`, suffix `-2`, `-3` on collision."
  (:refer-clojure :exclude [list]))
