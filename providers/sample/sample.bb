#!/usr/bin/env bb
;; The sample provider of the provider protocol (docs: deliberator.provider): newline-delimited JSON on
;; stdio, one process serving concurrent requests. Install it by copying sample.edn.example to
;; <home>/providers/sample.edn (edit the path), then `dlb sample echo --text hi`.
;; Operations: echo (read), count (read, streams `tick` events, honours cancel), fail (typed error), crash (exits 3).
(require '[cheshire.core :as json])

(def out (java.io.PrintWriter. System/out true))
(def cancelled (atom #{}))

(defn send! [m] (locking out (.println out (json/generate-string m)) (.flush out)))

(def described
  [{:op "echo" :class "read" :doc "Answers its text back with the provider's pid."
    :args {"text" {:type "string" :required true :doc "what to echo"}}}
   {:op "count" :class "read" :doc "Streams n tick events, delay-ms apart, then answers the total."
    :args {"n" {:type "int" :required true :doc "how many ticks"}
           "delay-ms" {:type "int" :doc "pause between ticks" :default 0}}
    :events ["tick"]}
   {:op "fail" :class "read" :doc "Always fails with a typed error (sample failure)."
    :args {"why" {:type "string" :doc "text of the error"}}}
   {:op "crash" :class "mutate" :doc "Exits the provider with code 3 without answering."
    :args {}}])

(defn handle [{:keys [id op args]}]
  (case op
    "op.describe" (send! {:id id :ok true :result described})
    "echo" (send! {:id id :ok true :result {:echo (:text args) :pid (.pid (java.lang.ProcessHandle/current))}})
    "count" (let [n (:n args) d (or (:delay-ms args) 0)]
              (loop [i 1]
                (cond (contains? @cancelled id) (send! {:id id :ok false :error {:type "cancelled" :message "count was cancelled"}})
                      (> i n) (send! {:id id :ok true :result {:total n}})
                      :else (do (send! {:id id :event "tick" :n i})
                                (when (pos? d) (Thread/sleep (long d)))
                                (recur (inc i))))))
    "fail" (send! {:id id :ok false :error {:type "sample-failure" :message (or (:why args) "the sample provider failed on purpose")
                                            :hint "this is what a provider error looks like"}})
    "crash" (do (binding [*out* *err*] (println "sample provider: crashing on request") (flush)) (System/exit 3))
    (send! {:id id :ok false :error {:type "unknown-op" :message (str "no operation " op)}})))

(def inflight (atom []))

(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (when-not (clojure.string/blank? line)
    (let [m (json/parse-string line true)]
      (if (:cancel m)
        (swap! cancelled conj (:id m))
        (swap! inflight conj
               (future (try (handle m)
                            (catch Throwable t (send! {:id (:id m) :ok false :error {:type "internal" :message (ex-message t)}})))))))))

;; stdin closed: finish what is running, then exit (a provider exits when the daemon lets go of it)
(run! deref @inflight)
(shutdown-agents)
