(ns deliberator.service.reload
  "`service.reload`: compile the service's module, bring the changed classes into the running container, say what
  happened.

  Hotswap image (the default): `mvn -o -q compile -pl <module> -am` (the description's `:reload :cmd`), a sha diff of every
  module's `target/classes` against its staging dir (`deliberator.service.staging`), only changed files are copied (temp
  name + rename, vanished files deleted), then the container log is watched from the copy on: HotswapAgent's
  `RELOAD … Reloading classes […] (autoHotswap)` line (a new bean: `add new spring bean '<name>'`), then the health probe UP
  and `quiet-ms` (2.5 s) without another `HOTSWAP AGENT` line (the Spring re-creation of a changed bean has no 'done' line);
  when Spring's only answer is `no change, ignore reloading`, 0.3 s (`settled-quiet-ms`).
  Budget 15 s from the copy. An `ERROR (org.hotswap.agent` line, no RELOAD line in the budget, health not UP afterwards, or a
  changed resource (not a class file: configuration is read at startup) ends in `service.restart`, said in `:fallback`.

  Plain-JRE image: the same compile, a sha diff of `target/classes` against the running jar (+ what was redefined since),
  the changed classes pushed through the daemon's JDWP client (`deliberator.jdwp`, body-only HotSwap); a refusal, a debugger
  holding the port, a class not loaded yet, a removed class or a changed resource end in a package build and a recreate of
  the container, said in `:fallback`.

  Events (to `:sink`): `service.reload.step {name step detail [n files redefined]}`, `step` one of `compiling`, `changed`,
  `staged`, `redefined`, `healthy`, `fallback restart`. Result `{name mechanism changed redefined took-ms log fallback
  reload-count}`; a compile error is `service/compile-failed` (`:lines`, `:errors [{file line col message}]`, `:log`, `:exit`).
  The compile output, the steps and the agent's lines are appended to `logs/reload/<name>-<ts>.log` and stored raw."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.jdwp :as jdwp]
            [deliberator.process :as process]
            [deliberator.runtime :as runtime]
            [deliberator.service :as service]
            [deliberator.service.description :as description]
            [deliberator.service.staging :as staging]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.time Instant)
           (java.util.zip ZipFile)))

(def default-budget-ms 15000)
(def default-quiet-ms 2500)
(def settled-quiet-ms
  "The quiet that ends a reload once Spring said it has nothing to re-create (B10)."
  300)

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defonce ^:private active (atom #{}))

(defn- class-name [rel] (-> rel (str/replace #"\.class$" "") (str/replace "/" ".")))
(defn- sig->class [sig] (-> sig (subs 1 (dec (count sig))) (str/replace "/" ".")))
(defn- class-file? [rel] (str/ends-with? rel ".class"))

;; compile ---------------------------------------------------------------------------------------

(def ^:private boilerplate #"^(-> \[Help|Re-run Maven|To see the full|For more information|\[Help|Failed to execute goal|COMPILATION ERROR|After correcting|\s+mvn |$)")

(defn compile-errors
  "Maven's output lines -> `{:lines [str] :errors [{:file :line :col :message}]}`: the compiler's `[ERROR]` lines
  (`path.java:[l,c] message` and the `symbol:`/`location:` lines after them), de-duplicated, boilerplate dropped."
  [lines]
  (let [errs (->> lines (keep #(second (re-matches #"\[ERROR\]\s?(.*)" %))) (remove #(re-find boilerplate %)) distinct vec)
        errs (let [located (filterv #(re-find #"\.java:\[\d+,\d+\]" %) errs)]   ; the compiler's own lines (+ their symbol/location lines)
               (if (seq located)
                 (vec (filter #(or (re-find #"\.java:\[\d+,\d+\]" %) (re-find #"^\s+(symbol|location|required|found|reason)" %)) errs))
                 errs))
        parsed (vec (for [l errs :let [m (re-matches #"(.*\.java):\[(\d+),(\d+)\]\s*(.*)" l)] :when m]
                      {:file (nth m 1) :line (parse-long (nth m 2)) :col (parse-long (nth m 3)) :message (nth m 4)}))]
    {:lines (if (seq errs) errs (vec (take-last 15 lines))) :errors parsed}))

(defn- run-cmd!
  "Runs argv in `dir` with JAVA_HOME `java-home`, appends every line to `logf`; the process tree is killed when the request
  is cancelled (`on-cancel!`) or after `process/default-budget-ms`. -> `{:exit :lines}`."
  [argv ^File dir java-home ^File logf on-cancel!]
  (with-open [w (io/writer logf :append true)]
    (try
      (process/run! argv {:dir dir :on-cancel! on-cancel! :what (str/join " " argv)
                          :extra-env (when java-home {"JAVA_HOME" java-home})
                          :line! (fn [l] (.write w l) (.write w "\n") (.flush w))})
      (catch clojure.lang.ExceptionInfo e
        (if (= :process/cannot-start (:type (ex-data e)))
          (fail :service/compile-failed (ex-message e) :lines [(ex-message e)] :hint "is the build tool on the daemon's PATH?")
          (throw e))))))

;; hotswap path ----------------------------------------------------------------------------------

(def ^:private reload-re #"Reloading classes \[([^\]]*)\] \(autoHotswap\)")
(def ^:private bean-re #"add new spring bean '([^']+)'")
(def ^:private agent-error-re #"ERROR \(org\.hotswap\.agent")
(def ^:private spring-re #"\(org\.hotswap\.agent\.plugin\.spring\.")
(def ^:private no-change-re #"no change, ignore reloading")

(defn- agent-lines [evs] (filterv #(str/includes? (str (:text %)) "HOTSWAP AGENT") evs))

(defn scan-agent
  "Log events -> `{:redefined [class] :beans [name] :error text|nil :last-at Instant|nil :settled bool :lines [text]}` of the
  HotswapAgent lines. `:settled`: after the last RELOAD line Spring answered and every one of its lines is `no change, ignore
  reloading` (nothing to re-create, so no long quiet is needed)."
  [evs]
  (let [as (agent-lines evs)
        after (seq (reverse (take-while #(not (re-find reload-re (str (:text %)))) (reverse as))))
        spring (filter #(re-find spring-re (str (:text %))) after)]
    {:redefined (vec (distinct (for [e as :let [m (re-find reload-re (:text e))] :when m
                                     c (str/split (second m) #",\s*") :when (seq c)] c)))
     :beans (vec (distinct (keep #(second (re-find bean-re (:text %))) as)))
     :error (some #(when (re-find agent-error-re (:text %)) (:text %)) as)
     :last-at (some->> (keep :at as) seq (apply max-key #(.toEpochMilli ^Instant %)))
     :settled (boolean (and (some #(re-find reload-re (str (:text %))) as)
                            (seq spring) (= (count after) (count spring))
                            (every? #(re-find no-change-re (str (:text %))) spring)))
     :lines (mapv :text as)}))

(defn- row-of [ws role]
  (first (:containers (runtime/status ws {:only [role]}))))

(defn- wait-for-agent
  "Polls the container log since `since` until the reload is done or `budget-ms` is spent.
  -> `{:ok true :redefined :beans :lines}` or `{:ok false :reason str :lines}`."
  [ws role since budget-ms quiet-ms step!]
  (let [deadline (+ (System/currentTimeMillis) budget-ms)
        seen (atom false)]
    (loop []
      (let [evs (try (vec (runtime/logs ws role {:since since})) (catch Exception _ []))
            a (scan-agent evs)
            reloaded? (or (seq (:redefined a)) (seq (:beans a)))]
        (when (and reloaded? (not @seen))
          (reset! seen true)
          (step! {:step "redefined" :detail (str "redefined " (str/join ", " (concat (:redefined a) (map #(str "bean " %) (:beans a))))
                                                 " (HotswapAgent)")
                  :redefined (:redefined a)}))
        (cond
          (:error a) {:ok false :reason (str "HotswapAgent reported: " (:error a)) :lines (:lines a)}
          (and reloaded? (:last-at a)
               (>= (- (System/currentTimeMillis) (.toEpochMilli ^Instant (:last-at a)))
                   (if (:settled a) (min quiet-ms settled-quiet-ms) quiet-ms))
               (= :healthy (:health (row-of ws role))))
          (assoc a :ok true)
          (> (System/currentTimeMillis) deadline)
          {:ok false :lines (:lines a)
           :reason (if reloaded?
                     (str "health not UP " budget-ms " ms after the copy")
                     (str "no HotswapAgent RELOAD line within " budget-ms " ms"))}
          :else (do (Thread/sleep 250) (recur)))))))

;; plain-JRE path --------------------------------------------------------------------------------

(defn jar-class-shas
  "rel path -> sha-256 of the entries of the fat jar's `BOOT-INF/classes/` (else of the jar root) as the JVM loaded them."
  [^File jar]
  (with-open [z (ZipFile. jar)]
    (let [entries (filter #(not (.isDirectory ^java.util.zip.ZipEntry %)) (enumeration-seq (.entries z)))
          prefix (if (some #(str/starts-with? (.getName ^java.util.zip.ZipEntry %) "BOOT-INF/classes/") entries) "BOOT-INF/classes/" "")]
      (into (sorted-map)
            (for [^java.util.zip.ZipEntry e entries :when (str/starts-with? (.getName e) prefix)
                  :let [n (subs (.getName e) (count prefix))] :when (and (seq n) (not (str/starts-with? n "META-INF/")))]
              [n (store/sha256 (with-open [in (.getInputStream z e)] (.readAllBytes in)))])))))

;; reload ----------------------------------------------------------------------------------------

(defn- finish-state!
  "Persists the reload state: the count of in-place reloads for this container, and the last result."
  [ws name row in-place? result extra]
  (let [old (staging/read-state ws name)
        same? (= (:container old) (:id row))
        base (if same? old {})]
    (staging/write-state! ws name
                          (merge (dissoc base :fallback)
                                 {:container (:id row)
                                  :count (+ (or (:count base) 0) (if in-place? 1 0))
                                  :at (store/now) :took-ms (:took-ms result) :changed (:changed result)}
                                 (when (:fallback result) {:fallback (:fallback result)})
                                 extra))))

(defn reload!
  "Compiles and reloads service `name` in its running container. See the namespace docstring.
  `opts`: `:sink`, `:budget-ms` (15000, the wait for the agent), `:quiet-ms` (2500), `:on-cancel!` (the protocol's: a cancelled
  request kills the compile's process tree)."
  [ws name {:keys [sink budget-ms quiet-ms on-cancel!] :or {budget-ms default-budget-ms quiet-ms default-quiet-ms}}]
  (let [d (or (description/read ws name)
              (fail :service/not-found (str "no service " (pr-str name) " in workspace " (store/ws-name ws))
                    :known (description/names ws) :hint "service.adopt a module, or dlb service list"))
        _ (when-not (description/runnable? d)
            (fail :service/not-runnable (str "service " name " is a " (clojure.core/name (:kind d)) ": nothing runs in a container to reload")))
        _ (when-not (description/reloadable? d)
            (fail :service/not-reloadable
                  (str "service " name " is a " (clojure.core/name (:kind d)) ": it runs its image as it is, there is no JVM to bring classes into")
                  :hint "service.restart recreates it after its description or its image changed"))
        role (runtime/role-name (description/role name))
        row (row-of ws role)
        _ (when-not (= :running (:state row))
            (fail :service/not-running (str "service " name " is not running (" (if row (clojure.core/name (:state row)) "no container") "): nothing to reload into")
                  :hint (str "dlb service start " name)))
        key [(store/ws-name ws) name]
        _ (when-not (let [[old _] (swap-vals! active conj key)] (not (contains? old key)))
            (fail :service/reload-in-progress (str "a reload of " name " is already running") :hint "wait for it"))
        kind (description/image-kind d)
        rc (description/reload-config d)
        t0 (System/nanoTime)
        ms #(quot (- (System/nanoTime) t0) 1000000)
        ts (str/replace (str (store/now)) #"[:]" "")
        logf (.toFile (store/path ws "logs" "reload" (str name "-" ts ".log")))
        _ (io/make-parents logf)
        logln (fn [s] (spit logf (str s "\n") :append true))
        step! (fn [m]
                (logln (str "== " (:step m) (when (:detail m) (str ": " (:detail m)))))
                (when sink (sink (merge {:event "service.reload.step" :name name} m))))
        persist-raw! (fn []
                       (store/raw-put! ws (.getBytes ^String (slurp logf) "UTF-8")
                                       {:source :build :op (str "service.reload " name) :env nil :at (store/now) :content-type "text/plain"}))]
    (try
      (when-not (seq (:cmd rc))
        (fail :service/no-reload-cmd (str "service " name " has no :reload :cmd and its build is not a Maven command to derive one from")
              :hint "add :reload {:cmd [argv] :dir …} to the description"))
      (let [mod (description/module-dir ws d)
            dir (.getCanonicalFile (io/file mod (:dir rc)))
            _ (step! {:step "compiling" :detail (str/join " " (:cmd rc))})
            jh (some-> (get-in d [:build :java]) service/jdk)
            {:keys [exit lines]} (run-cmd! (:cmd rc) dir jh logf on-cancel!)]
        (when-not (zero? exit)
          (let [{:keys [lines errors]} (compile-errors lines)]
            (persist-raw!)
            (fail :service/compile-failed (str "compile of " name " failed: "
                                               (if (seq errors)
                                                 (str/join "; " (for [e (take 3 errors)] (str (.getName (io/file (:file e))) ":" (:line e) " " (:message e))))
                                                 (or (first lines) "see the log")))
                  :lines lines :errors errors :log (str logf) :exit exit :hint "fix the compiler errors above and reload again")))
        (let [target (io/file mod "target/classes")
              result
              (if (= :hotswap kind)
                ;; ---- hotswap: stage, watch the agent ----
                (let [dirs (:dirs (description/exploded ws d))
                      label (fn [i rel] (if (zero? i) rel (str (nth (:modules rc) (dec i)) "/" rel)))
                      diffs (vec (map-indexed (fn [i {:keys [seed staging]}]
                                                (assoc (staging/diff (io/file seed) (io/file staging)) :i i :seed seed :staging staging))
                                              dirs))
                      changed (vec (for [{:keys [i changed]} diffs rel changed] (label i rel)))
                      removed (vec (for [{:keys [i removed]} diffs rel removed] (label i rel)))
                      resources (vec (remove class-file? (concat changed removed)))]
                  (step! {:step "changed" :detail (str "changed " (count changed) (when (seq removed) (str ", removed " (count removed)))) :n (count changed) :files changed})
                  (if (and (empty? changed) (empty? removed))
                    {:changed [] :redefined [] :mechanism "hotswap-agent"}
                    (let [since (.minusMillis (Instant/now) 200)]
                      (doseq [{:keys [seed staging]} diffs] (staging/sync! (io/file seed) (io/file staging)))
                      (step! {:step "staged" :detail "copied into the staging dir"})
                      (if (seq resources)
                        {:changed changed :redefined [] :mechanism "hotswap-agent"
                         :fallback (str "resource changed (" (str/join ", " (take 3 resources)) (when (> (count resources) 3) ", …") "): configuration is read at startup")}
                        (let [w (wait-for-agent ws role since budget-ms quiet-ms step!)]
                          (doseq [l (:lines w)] (logln l))
                          (if (:ok w)
                            (do (step! {:step "healthy" :detail "health UP, agent quiet"})
                                {:changed changed :redefined (vec (concat (:redefined w) (map #(str "bean " %) (:beans w)))) :mechanism "hotswap-agent"})
                            {:changed changed :redefined [] :mechanism "hotswap-agent" :fallback (:reason w)}))))))
                ;; ---- plain JRE: JDWP RedefineClasses ----
                (let [jar (or (description/artifact ws d) (fail :service/not-built (str "no built jar for " name) :hint (str "dlb service build " name)))
                      stamp [(.length jar) (.lastModified jar)]
                      old (staging/read-state ws name)
                      overlay (if (= stamp (:jar-stamp old)) (:redefined-shas old) {})
                      base (merge (jar-class-shas jar) overlay)
                      now (staging/sha-map target)
                      {:keys [changed removed]} (staging/diff-maps now base)
                      resources (vec (remove class-file? (concat changed removed)))
                      classes (filterv class-file? changed)]
                  (step! {:step "changed" :detail (str "changed " (count changed)) :n (count changed) :files changed})
                  (cond
                    (and (empty? changed) (empty? removed)) {:changed [] :redefined [] :mechanism "jdwp"}
                    (seq resources) {:changed changed :redefined [] :mechanism "jdwp"
                                     :fallback (str "resource changed (" (str/join ", " (take 3 resources)) "): the jar must be rebuilt")}
                    (seq removed) {:changed changed :redefined [] :mechanism "jdwp"
                                   :fallback (str "class files removed (" (str/join ", " (take 3 removed)) "): the JVM cannot unload them")}
                    :else
                    (let [port (get (:ports row) (:jdwp d))
                          r (if port (jdwp/redefine! "127.0.0.1" port (str target) classes) {:ok false :exception "the description has no :jdwp port"})]
                      (logln (pr-str r))
                      (cond
                        (not (:ok r)) {:changed changed :redefined [] :mechanism "jdwp"
                                       :fallback (if (:exception r)
                                                   (str "JDWP: " (:exception r) " (a debugger may hold the port)")
                                                   (str "the JVM refused the redefinition: " (clojure.core/name (or (:error r) :unknown))))}
                        (seq (:skipped r)) {:changed changed :redefined [] :mechanism "jdwp"
                                            :fallback (str "class not loaded yet (" (str/join ", " (take 3 (:skipped r))) "): a later load would read the old jar")}
                        :else (do (step! {:step "redefined" :detail (str "redefined " (count (:redefined r)) " class(es) over JDWP")})
                                  {:changed changed :mechanism "jdwp"
                                   :state-extra {:jar-stamp stamp :redefined-shas (merge overlay (select-keys now classes))}
                                   :redefined (mapv sig->class (:redefined r))}))))))
              fallback (:fallback result)]
          (when fallback
            (step! {:step "fallback restart" :detail fallback})
            (when (= :plain kind) (service/build! ws name {:sink sink :on-cancel! on-cancel!}))
            (service/restart! ws name {:sink sink}))
          (let [row2 (or (row-of ws role) row)
                out (cond-> {:name name :mechanism (:mechanism result) :changed (:changed result) :redefined (vec (:redefined result))
                             :took-ms (ms) :log (str logf)}
                      fallback (assoc :fallback fallback))
                _ (finish-state! ws name row2 (boolean (and (not fallback) (seq (:changed result)))) out (:state-extra result))
                st (staging/read-state ws name)]
            (logln (pr-str out))
            (persist-raw!)
            (assoc out :reload-count (if (= (:container st) (:id row2)) (:count st) 0)))))
      (finally (swap! active disj key)))))
