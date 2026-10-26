(ns deliberator.fake-kind
  "The kinds every engine test runs against; no I/O.

  `fake` (verbs none or `ok`): the response is the step body (json when it starts with { or [,
  else text; `ok\\n` when empty). Directives: `set name=value` (a fact; `at` is parsed as instant,
  numbers for number/duration facts), `sleep <ms>` (interruptible), `fail <message>` (throws, with
  the request/response bytes in ex-data), `log <text>` (appends to the shared `log` and streams a
  line), `display <text>`. Always publishes fact `n` (the attempt) and `len`. Every start/end goes
  into `trace`.

  `tail` (concurrent): armed with the current length of `log`, then waits `window` ms (directive
  `for`, default 200) and publishes `lines`, the number of log entries added since it was armed;
  its display is those lines."
  (:require [clojure.string :as str]
            [deliberator.scenario.kinds :as kinds])
  (:import (java.time Instant)))

(def trace (atom []))
(def log (atom []))
(def ctxs "every ctx a `fake` attempt was run with" (atom []))

(defn reset-state! [] (reset! trace []) (reset! log []) (reset! ctxs []) nil)

(defn- dirs [step name] (keep #(when (= name (:name %)) (:value %)) (:directives step)))

(defn- coerce [k v]
  (case k
    ("status" "n" "len" "count") (Long/parseLong v)
    "took" (Long/parseLong v)
    "at" (Instant/parse v)
    v))

(defn- run-fake [ctx step]
  (let [n (:n step)]
    (swap! trace conj [:start n])
    (swap! ctxs conj ctx)
    (doseq [t (dirs step "log")] (swap! log conj t) ((:line! ctx) t))
    (when-let [ms (first (dirs step "sleep"))] (Thread/sleep (Long/parseLong ms)))
    (when-let [m (first (dirs step "fail"))]
      (swap! trace conj [:fail n])
      (throw (ex-info m {:type :fake/boom :hint "the fake kind was told to fail"
                         :request-bytes (.getBytes "req" "UTF-8") :request-ext "http"
                         :response-bytes (.getBytes "partial" "UTF-8")})))
    (swap! trace conj [:end n])
    (let [bs (or (:body step) (.getBytes "ok\n" "UTF-8"))
          resp (String. ^bytes bs "ISO-8859-1")
          json? (re-find #"^\s*[\[{]" resp)
          facts (into {"n" (:attempt ctx) "len" (count resp)}
                      (for [kv (dirs step "set") :let [[k v] (str/split kv #"=" 2)]] [k (coerce k v)]))]
      (cond-> {:facts facts
               :request-bytes (.getBytes (str (:verb step) " " (:target step) " #" (:attempt ctx)) "UTF-8")
               :request-ext "http"
               :response-bytes bs
               :response-ext (if json? "json" "txt")
               :lines (count (str/split-lines resp))}
        (first (dirs step "display")) (assoc :display (first (dirs step "display")))))))

(def fake
  {:kind "fake" :verbs #{nil "ok"} :target :optional
   :directives [{:name "set" :hint "fact=value" :repeatable? true}
                {:name "sleep" :hint "ms"} {:name "fail" :hint "message"}
                {:name "log" :hint "text" :repeatable? true} {:name "display" :hint "text"}]
   :facts [{:name "n" :type :number} {:name "len" :type :number} {:name "status" :type :number}
           {:name "count" :type :number} {:name "id" :type :id} {:name "at" :type :instant}
           {:name "took" :type :duration} {:name "msg" :type :string} {:name "header.*" :type :string}]
   :capture-roots #{"$"} :concurrent? false :run run-fake})

(def tail
  {:kind "tail" :verbs #{nil} :target :optional
   :directives [{:name "for" :hint "ms"}]
   :facts [{:name "lines" :type :number}]
   :capture-roots #{} :concurrent? true
   :arm (fn [_ step] (swap! trace conj [:arm (:n step)]) (count @log))
   :run (fn [ctx step]
          (swap! trace conj [:start (:n step)])
          (Thread/sleep (Long/parseLong (or (first (dirs step "for")) "200")))
          (let [lines (subvec @log (min (:armed ctx) (count @log)))]
            (swap! trace conj [:end (:n step)])
            {:facts {"lines" (count lines)} :lines (count lines)
             :display (str/join "\n" lines)}))})

(defn register! [] (kinds/register! fake) (kinds/register! tail))
(defn unregister! [] (kinds/unregister! "fake") (kinds/unregister! "tail"))

(defn with-kinds
  "A `:once` fixture: `fake` and `tail` are in the JVM-global registry for the namespace's tests and gone afterwards
  (loading this namespace registers nothing, so no other namespace's view of `scenario.kinds` depends on load order)."
  [f]
  (register!)
  (try (f) (finally (unregister!))))
