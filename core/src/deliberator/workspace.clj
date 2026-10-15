(ns deliberator.workspace
  "Workspaces. Contract: `(load ws-name)`, `(save! ws)`, `(current-session ws)`,
  `(set-current-session! ws key)`. `workspace.edn` schema: `{:name :created :environments [names]
  :repos [...] :current-session key-or-nil}`. `load` of a workspace that was never saved returns the
  default map without writing; `save!` creates the directories. Unknown keys survive a round trip."
  (:refer-clojure :exclude [load])
  (:require [deliberator.store :as store]))

(defn- file [ws] (store/path ws "workspace.edn"))

(defn load
  "The workspace map for `ws-name` (name or map)."
  [ws-name]
  (let [n (store/ws-name ws-name)]
    (or (store/read-edn (file n))
        {:name n :created (store/now) :environments [] :repos [] :current-session nil})))

(defn save!
  "Persists the workspace map atomically; returns it."
  [ws]
  (store/write-edn! (file ws) ws)
  ws)

(defn current-session
  "The current session key (string) or nil, read from disk."
  [ws]
  (:current-session (load ws)))

(defn set-current-session!
  "Sets (or with nil clears) the current session key; returns the saved workspace."
  [ws key]
  (save! (assoc (load ws) :current-session (some-> key store/safe-name))))
