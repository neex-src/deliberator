(ns deliberator.logrefs
  "From a log line to the code (`logs.refs`): the places of a log that name source code, resolved to files of the
  ATTACHED checkouts. `(scan ws path)` -> `{:path :lines :service :unresolved :refs [...]}`; a ref is a clickable span of one line:

    `{:n <1-based line> :from :to <columns> :text <the span> :kind \"frame\"|\"logger\"|\"exception\"|\"node\"
      [:class :method :logger] [:line] [:col] :targets [{:file <absolute> :repo <name> :rel <path in the repo>} ...]}`

  Only spans that resolve are refs (`:unresolved` counts the others): a frame of a class that no attached checkout holds is not a link.
  The shapes, searched anywhere in a line (a mirrored `logs/services/` line carries `<instant> out ` before the text):

    frame      `at pkg.Cls$In.m(File.java:15)`, also `at app//pkg...` and `at java.base/pkg...`; the span is `File.java:15`.
               The file is resolved by NAME: `pkg/path/File.java` must be the tail of a source file of a checkout.
    logger     the logback token `c.b.billing.Pricing:15 - msg` (`%logger{36}:%line`, the same one `markers` matches). logback abbreviates
               the packages, so the file's directories are compared right aligned with `markers/logger-matches?`; the class name is exact.
    exception  a throwable header: `Caused by: x.y.Z: msg`, `Suppressed: x.y.Z`, `Exception in thread \"t\" x.y.Z`, or `x.y.Z: msg` at the
               start of a line. The span is the class; the file is `x/y/Z.<ext>` of a checkout, no line (the IDE finds the declaration).
    node       a Node frame `at fn (file:///var/task/index.mjs:42:7)` or `at /var/task/index.mjs:42:7` (also inside the JSON `stack`
               array of a Lambda error). The span is `/var/task/index.mjs:42:7`; the file is the service's own (below).

  Which repository. The log names its service when it is `sessions/<k>/logs/log-service-<n>-<ts>[-i].log` (the file of `logs.fetch` of the
  target `log/service-<n>`) or `logs/services/service-<n>/<date>.log` (the mirror of the runtime): the description `services/<n>.edn` says
  its `:repo` and `:module`. When that repository holds the file it is the only target (the right repository when two have the same
  class); otherwise every attached checkout that holds it is a target, in attach order, and the IDE asks. A log the daemon cannot
  attribute to a service has all candidates.

  Node. A `:node-lambda` description maps the deployed root to its sources: `:task-root` (default `/var/task`, where AWS puts the
  function's code) is `<repo>/<module>`; a frame is a target only when that file exists. Nothing reads source maps: the deployed
  files must be the sources (a bundled function has none to point at). `:task-root` is not validated by `service.description`.

  The source files of a checkout come from `git ls-files -co --exclude-standard` (a repository root with `.git`: ignored build output
  is out), else a walk that skips `.git .gradle .idea .scratch node_modules target build dist out`; only `java kt kts groovy scala
  clj cljc cljs`. The list is cached for a few seconds per attached set."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.git :as git]
            [deliberator.markers :as markers]
            [deliberator.repo :as repo]
            [deliberator.service.description :as description]
            [deliberator.store :as store])
  (:import (java.io BufferedReader File)
           (java.nio.file FileVisitResult Files LinkOption Path SimpleFileVisitor)
           (java.util.regex Matcher)))

;; spans of one line (pure) ---------------------------------------------------------------------

;; No repeated groups below (a repeated group recurses in java.util.regex: a long dotted token overflowed the stack in `markers`);
;; the one group with a count is bounded to two.
(def ^:private frame-re #"\bat\s+(?:[\w.$@-]*/){0,2}([\w$.]+)\.([\w$<>]+)\(([^():\s/\\]+):(\d{1,9})\)")
(def ^:private logger-re #"(?:^|\s)([A-Za-z_$][\w$.]*):(\d{1,9})\s+-\s")
(def ^:private caused-re #"(?:Caused by:|Suppressed:|Exception in thread \"[^\"]*\")\s+([A-Za-z_$][\w$.]*)")
(def ^:private bare-re #"\A(?:\S+ (?:out|err) )?([A-Za-z_$][\w$.]*)(?::\s|:\z|\z)")
(def ^:private node-re #"(?<![\w.-])(?:file://)?(/[^\s():\"']+\.(?:mjs|cjs|js|jsx|ts|tsx)):(\d{1,9}):(\d{1,9})")

(defn- dotted-ok?
  "Every dot-separated segment of `s` is an identifier (no empty segment, none starting with a digit)."
  [^String s]
  (and (not (str/ends-with? s ".")) (not (str/includes? s ".."))
       (every? #(re-matches #"[A-Za-z_$][\w$]*" %) (str/split s #"\." -1))))

(defn- matches [^java.util.regex.Pattern re ^String s f]
  (let [^Matcher m (re-matcher re s)]
    (loop [acc []]
      (if (.find m) (recur (if-let [x (f m)] (conj acc x) acc)) acc))))

(defn spans
  "The unresolved spans of `line`: `[{:kind :from :to ...}]` in column order (`:kind` is a keyword: :frame :logger :exception :node)."
  [^String line]
  (let [at? (str/includes? line "at")]
    (->> (concat
          (when (and at? (str/includes? line "("))
            (matches frame-re line
                     (fn [^Matcher m]
                       (when (dotted-ok? (.group m 1))
                         {:kind :frame :class (.group m 1) :method (.group m 2) :file-name (.group m 3)
                          :line (Long/parseLong (.group m 4)) :from (.start m 3) :to (.end m 4)}))))
          (when (str/includes? line ":")
            (matches logger-re line
                     (fn [^Matcher m]
                       (when (dotted-ok? (.group m 1))
                         {:kind :logger :logger (.group m 1) :line (Long/parseLong (.group m 2))
                          :from (.start m 1) :to (.end m 2)}))))
          (matches caused-re line
                   (fn [^Matcher m]
                     (when (and (dotted-ok? (.group m 1)) (str/includes? (.group m 1) "."))
                       {:kind :exception :class (.group m 1) :from (.start m 1) :to (.end m 1)})))
          (matches bare-re line
                   (fn [^Matcher m]
                     (when (and (dotted-ok? (.group m 1)) (str/includes? (.group m 1) "."))
                       {:kind :exception :class (.group m 1) :from (.start m 1) :to (.end m 1)})))
          (when (and at? (str/includes? line "/"))
            (matches node-re line
                     (fn [^Matcher m]
                       (when (re-find #"\bat\s" (subs line 0 (.start m 1)))
                         {:kind :node :path (.group m 1) :line (Long/parseLong (.group m 2)) :col (Long/parseLong (.group m 3))
                          :from (.start m 1) :to (.end m 3)})))))
         (sort-by :from)
         (reduce (fn [acc s] (if (some #(and (= (:from %) (:from s)) (= (:to %) (:to s))) acc) acc (conj acc s))) []))))

;; the source files of the attached checkouts ---------------------------------------------------

(def ^:private source-exts #{"java" "kt" "kts" "groovy" "scala" "clj" "cljc" "cljs"})
(def ^:private skip-dirs #{".git" ".gradle" ".idea" ".scratch" "node_modules" "target" "build" "dist" "out"})

(defn- ext-of [^String name] (let [i (.lastIndexOf name ".")] (when (pos? i) (subs name (inc i)))))

(defn- walk-sources [^Path root]
  (let [out (volatile! [])]
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [dir _]
         (if (and (not= dir root) (contains? skip-dirs (str (.getFileName ^Path dir))))
           FileVisitResult/SKIP_SUBTREE
           FileVisitResult/CONTINUE))
       (visitFile [f _]
         (when (contains? source-exts (ext-of (str (.getFileName ^Path f))))
           (vswap! out conj (str (.relativize root ^Path f))))
         FileVisitResult/CONTINUE)
       (visitFileFailed [_ _] FileVisitResult/CONTINUE)))
    @out))

(defn- source-files
  "Relative paths (with /) of the source files of the checkout at `dir`."
  [dir]
  (let [git? (.exists (io/file (str dir) ".git"))
        r (when git?
            (try (git/run dir (concat ["ls-files" "-z" "-co" "--exclude-standard" "--"] (map #(str "*." %) (sort source-exts))))
                 (catch Exception _ nil)))]
    (if (and r (zero? (:exit r)))
      (vec (remove str/blank? (str/split (:out r) #"\u0000")))
      (walk-sources (.toPath (io/file (str dir)))))))

(defn- build-index [checkouts]
  (let [entries (for [[i {:keys [name path]}] (map-indexed vector checkouts)
                      rel (source-files path)
                      :let [j (.lastIndexOf ^String rel "/")
                            fname (subs rel (inc j))]]
                  {:file (str (io/file path rel)) :repo name :rel rel :order i
                   :dir (if (neg? j) "" (subs rel 0 j)) :name fname
                   :base (subs fname 0 (.lastIndexOf ^String fname "."))})]
    {:by-name (group-by :name entries) :by-base (group-by :base entries)}))

(def ^:private cache (atom {}))
(def ^:private ttl-ms 5000)

(defn clear-cache! [] (reset! cache {}))

(defn source-index
  "`{:by-name {\"Pricing.java\" [entry]} :by-base {\"Pricing\" [entry]}}` over the attached checkouts of `ws` (cached a few seconds)."
  [ws]
  (let [cs (repo/checkouts ws)
        k (mapv (juxt :name :path) cs)
        now (System/currentTimeMillis)
        {:keys [at idx key]} (get @cache (store/ws-name ws))]
    (if (and at (= key k) (< (- now at) ttl-ms))
      idx
      (let [idx (build-index cs)]
        (swap! cache assoc (store/ws-name ws) {:at now :key k :idx idx})
        idx))))

;; resolution ------------------------------------------------------------------------------------

(defn- outer [^String fqn] (first (str/split fqn #"\$" 2)))

(defn- pkg-path [^String fqn]
  (let [i (.lastIndexOf fqn ".")] (if (neg? i) "" (str/replace (subs fqn 0 i) "." "/"))))

(defn- simple-name [^String fqn] (subs fqn (inc (.lastIndexOf fqn "."))))

(defn- dir-ok? [^String dir ^String pkg] (or (= "" pkg) (= dir pkg) (str/ends-with? dir (str "/" pkg))))

(defn- frame-entries [idx {:keys [class file-name]}]
  (filter #(dir-ok? (:dir %) (pkg-path (outer class))) (get-in idx [:by-name file-name])))

(defn- exception-entries [idx {:keys [class]}]
  (let [c (outer class)]
    (filter #(dir-ok? (:dir %) (pkg-path c)) (get-in idx [:by-base (simple-name c)]))))

(defn- logger-entries [idx {:keys [logger]}]
  (let [l (outer logger)
        n (count (str/split l #"\."))]
    (filter (fn [{:keys [dir base]}]
              (let [comps (conj (if (= "" dir) [] (str/split dir #"/")) base)]
                (and (>= (count comps) n)
                     (markers/logger-matches? (str/join "." (take-last n comps)) l))))
            (get-in idx [:by-base (simple-name l)]))))

(defn- target [e] (select-keys e [:file :repo :rel]))

(defn- rank
  "The entries that count for a log of service `svc`: its repository's when it holds the file, else all; module first, main before test."
  [entries svc]
  (let [mine (when svc (seq (filter #(= (:repo svc) (:repo %)) entries)))
        mod (some-> (:module svc) (str "/"))]
    (->> (or mine entries)
         (sort-by (juxt #(if (and mine mod (str/starts-with? (:rel %) mod)) 0 1)
                        :order
                        #(if (str/includes? (str "/" (:rel %)) "/src/test/") 1 0)
                        :rel))
         (mapv target))))

;; node: the deployed root is the service's module -----------------------------------------------

(defn- node-roots [ws svc]
  (let [lambdas (filter #(= :node-lambda (:kind %)) (description/list ws))
        used (if (and svc (= :node-lambda (:kind svc))) [svc] lambdas)
        checkouts (repo/checkouts ws)]
    (vec (for [d used
               :let [dir (try (.toPath (.getCanonicalFile (description/module-dir ws d))) (catch Exception _ nil))
                     repo-path (some-> (first (filter #(= (:repo d) (:name %)) checkouts)) :path)]
               :when dir]
           {:root (str/replace (or (:task-root d) "/var/task") #"/+$" "")
            :dir dir :repo (:repo d) :repo-path (some-> repo-path (-> io/file .getCanonicalFile .toPath))}))))

(defn- node-targets [roots ^String path]
  (vec (distinct
        (for [{:keys [root dir repo repo-path]} roots
              :when (str/starts-with? path (str root "/"))
              :let [f (.normalize (.resolve ^Path dir ^String (subs path (inc (count root)))))]
              :when (and (.startsWith f ^Path dir) (Files/isRegularFile f (make-array LinkOption 0)))]
          {:file (str f) :repo repo
           :rel (str (if (and repo-path (.startsWith f ^Path repo-path)) (.relativize ^Path repo-path f) f))}))))

;; which service wrote the log -------------------------------------------------------------------

(defn service-of
  "The description of the service whose log `path` is (see the ns docstring), or nil."
  [ws ^String path]
  (let [name (or (some-> (re-find #"/logs/services/service-([^/]+)/[^/]+\.log\z" path) second)
                 (some-> (re-find #"/log-service-(.+?)-\d{8}-\d{6}(?:-\d+)?\.log\z" path) second))]
    (when name (try (description/read ws name) (catch Exception _ nil)))))

;; the scan --------------------------------------------------------------------------------------

(defn- resolve-span [idx roots svc s]
  (let [targets (case (:kind s)
                  :frame (rank (frame-entries idx s) svc)
                  :logger (rank (logger-entries idx s) svc)
                  :exception (rank (exception-entries idx s) svc)
                  :node (node-targets roots (:path s)))]
    (assoc s :targets targets)))

(defn- ref-of [n ^String line s]
  (-> (select-keys s [:kind :class :method :logger :line :col :from :to :targets])
      (assoc :n n :text (subs line (:from s) (:to s)))
      (update :kind name)))

(defn scan
  "Reads the log file at `path` (lines end at \\n, \\r\\n or \\r, as an editor counts them) and answers its refs; see the ns docstring."
  [ws path]
  (let [p (str path)
        svc (service-of ws p)
        idx (source-index ws)
        roots (node-roots ws svc)]
    (with-open [^BufferedReader rdr (io/reader p :encoding "UTF-8")]
      (loop [n 1, refs (transient []), unresolved 0]
        (if-let [line (.readLine rdr)]
          (let [ss (map #(resolve-span idx roots svc %) (spans line))
                ok (filter (comp seq :targets) ss)]
            (recur (inc n)
                   (reduce #(conj! %1 (ref-of n line %2)) refs ok)
                   (+ unresolved (- (count ss) (count ok)))))
          {:path p :lines (dec n) :service (:name svc) :refs (persistent! refs) :unresolved unresolved})))))
