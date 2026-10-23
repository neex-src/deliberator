(ns deliberator.steps.shell
  "Kind `shell`: `### shell [name]`, the body is a bash script run with `bash -c` in `@cwd` (relative to the
  scenario's directory, default that directory). stderr is merged into stdout, so the output is the transcript a terminal
  shows; every line is streamed as it arrives (`line!`) and the exact bytes are the response (`response.txt`, the script is
  `request.sh`). Facts: `exit` (number), `out` (the output as text, at most the last 64 KB), `lines` (number); `$` paths
  work when the whole output is JSON. A non-zero exit is a step error unless an `@assert`/`@until` of the step names `exit`
  (then the checks decide). The script sees `DELIBERATOR_HOME DLB_WS DLB_SESSION DLB_ENV DLB_RUN_ID` on top of the daemon's
  environment. Default timeout 10 minutes; a timeout or cancel kills the process and its children. Refused for a `:remote`
  environment."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.common :as c]
            [deliberator.store :as store])
  (:import (java.io BufferedReader ByteArrayOutputStream File FilterInputStream InputStream InputStreamReader)
           (java.nio.charset StandardCharsets)))

(def ^:private out-fact-max 65536)

(defn names-fact?
  "True when an @assert/@until directive of `step` has one of `operands` as its operand (the first word)."
  [step operands]
  (some (fn [v] (some #(re-find (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote %) "(\\s|$)")) v) operands))
        (concat (c/dirs step "assert") (c/dirs step "until"))))

(defn- tee
  "An InputStream that copies every byte it hands out into `sink`."
  ^InputStream [^InputStream in ^ByteArrayOutputStream sink]
  (proxy [FilterInputStream] [in]
    (read
      ([] (let [b (.read in)] (when (>= b 0) (.write sink b)) b))
      ([^bytes bs] (let [n (.read in bs)] (when (pos? n) (.write sink bs 0 n)) n))
      ([^bytes bs off len] (let [n (.read in bs (int off) (int len))] (when (pos? n) (.write sink bs (int off) n)) n)))))

(defn- kill-tree! [^Process p]
  (doseq [h (iterator-seq (.iterator (.descendants (.toHandle p))))] (.destroyForcibly ^java.lang.ProcessHandle h))
  (.destroyForcibly p))

(defn- cwd-of [ctx step]
  (let [base (or (:scenario-dir ctx) (System/getProperty "user.dir"))
        f (if-let [d (c/dir step "cwd")] (let [f (File. ^String d)] (if (.isAbsolute f) f (File. ^String base ^String d))) (File. ^String base))
        f (.getCanonicalFile f)]
    (when-not (.isDirectory f)
      (throw (ex-info (str "@cwd " f " is not a directory") {:type :shell/cwd :hint "@cwd is relative to the scenario's directory"})))
    f))

(defn- json-body [^String text]
  (let [t (str/trim text)]
    (when (or (str/starts-with? t "{") (str/starts-with? t "["))
      (try (json/read-str t) (catch Exception _ nil)))))

(defn- display [^String text]
  (let [ls (str/split-lines text) n (count ls)]
    (if (<= n 50)
      text
      (str "(… " (- n 50) " earlier lines in response.txt)\n" (str/join "\n" (take-last 50 ls)) "\n"))))

(defn run [ctx step]
  (when (= :remote (some-> ctx :env :kind))
    (throw (ex-info (str "shell steps run only against a local environment; " (c/env-name ctx) " is remote")
                    {:type :shell/remote-env :hint "run this scenario with --env local"})))
  (let [script (c/text (or (:body step) (byte-array 0)))
        _ (when (str/blank? script) (throw (ex-info "a shell step needs a script in its body" {:type :shell/empty})))
        dir (cwd-of ctx step)
        pb (doto (ProcessBuilder. ^java.util.List ["bash" "-c" script])
             (.directory dir)
             (.redirectErrorStream true))
        _ (doto (.environment pb)
            (.put "DELIBERATOR_HOME" (str (store/home)))
            (.put "DLB_WS" (str (:ws ctx)))
            (.put "DLB_SESSION" (str (:session ctx)))
            (.put "DLB_ENV" (str (c/env-name ctx)))
            (.put "DLB_RUN_ID" (str (:correlation ctx))))
        p (.start pb)
        _ (.close (.getOutputStream p))
        out (ByteArrayOutputStream.)
        line! (or (:line! ctx) (fn [_]))
        reader (future
                 (with-open [r (BufferedReader. (InputStreamReader. (tee (.getInputStream p) out) StandardCharsets/UTF_8))]
                   (loop [n 0]
                     (if-let [l (.readLine r)] (do (line! l) (recur (inc n))) n))))
        exit (try (.waitFor p)
                  (catch InterruptedException e
                    (kill-tree! p)
                    (throw e)))
        n @reader
        bs (.toByteArray out)
        text (c/text bs)
        out-fact (if (> (count text) out-fact-max) (str "…" (subs text (- (count text) out-fact-max))) text)
        result (cond-> {:facts {"exit" exit "out" (str/trimr out-fact) "lines" n}
                        :lines n
                        :request-bytes (c/utf8 script) :request-ext "sh"
                        :response-bytes bs :response-ext "txt"
                        :display (display text)}
                 (json-body text) (assoc :body-json (json-body text)))]
    (if (and (not (zero? exit)) (not (names-fact? step ["exit"])))
      (throw (ex-info (str "exit " exit (when-let [l (last (str/split-lines text))] (str ": " l)))
                      {:type :shell/exit :exit exit :request-bytes (c/utf8 script) :response-bytes bs
                       :hint (str "the script exited " exit "; assert on exit (# @assert exit == " exit ") if that is expected")}))
      result)))

(def kind
  {:kind "shell" :verbs #{nil} :target :none
   :directives [{:name "cwd" :hint "<dir>" :values [".."] :doc "working directory, relative to the scenario's directory"}]
   :facts [{:name "exit" :type :number} {:name "out" :type :string} {:name "lines" :type :number}]
   :capture-roots #{"$"} :concurrent? false
   :default-timeout-ms 600000
   :run run})

(kinds/register! kind)
