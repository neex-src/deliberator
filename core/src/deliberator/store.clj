(ns deliberator.store
  "The on-disk store: `$DELIBERATOR_HOME` (default `~/.local/share/deliberator`), workspace paths,
  atomic EDN read/write and the content-addressed raw store. Nothing here ever deletes.

  `ws` is a workspace name (string or keyword) or a loaded workspace map (`:name`).

  Home resolution: dynamic `*home*` (tests), then env `DELIBERATOR_HOME`, then the default.
  `(path ws & parts)` builds `<home>/workspaces/<ws>/parts...` (architecture section 3).

  `(write-edn! path data)` pretty-prints to a sibling temp file `.<name>.<id>.tmp`, fsyncs, then
  renames atomically over the target: a writer killed in between leaves the target intact (and a
  stray temp file). `(read-edn path)` reads it back (`#inst` as `java.time.Instant`), nil if absent.

  Raw store contract: `(raw-put! ws bytes meta) -> {:sha :path :bytes n :new? bool}`, `(raw-get ws
  sha) -> bytes`, `(raw-meta ws sha) -> map`, `(raw-path ws sha)`. The blob lives at
  `raw/sha256/<2>/<62>` (lowercase hex sha-256) beside `<62>.meta.edn`; it is written once and made
  read-only; a second put of the same bytes returns `:new? false` and keeps the first meta. `meta`
  must carry `:source` (one of `sources`), `:op`, `:env` (key present, may be nil), `:at` (Instant)
  and `:content-type`; `raw-put!` adds `:bytes` and `:sha`. To add a source kind, extend the
  `sources` set below, one line. `(link! ws sha target-path)` hard-links a raw blob into a run step
  directory, copying across filesystems."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.walk :as walk])
  (:import (java.io File)
           (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException FileAlreadyExistsException Files
                          LinkOption Path StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util UUID)))

(def sources
  "Allowed `:source` values of a raw blob. Extend here when a new kind of fetcher appears."
  #{:aws :http :podman :sql :file :note :build})

(def ^:dynamic *home*
  "Override of the home directory (string or Path); tests bind it to a temporary directory."
  nil)

(def ^:dynamic *clock*
  "Zero-arg fn returning the current `Instant`; tests bind it for deterministic timestamps."
  #(Instant/now))

(def ^:dynamic *before-rename*
  "Test seam: called with the temp path after it is fully written and before the rename."
  nil)

(defn now [] (*clock*))

(def no-default-home-message
  "The one line printed when `DELIBERATOR_NO_DEFAULT_HOME` forbids the default home."
  "DELIBERATOR_NO_DEFAULT_HOME is set: pass --home or DELIBERATOR_HOME")

(defn home
  "The `$DELIBERATOR_HOME` directory as a Path. With `$DELIBERATOR_NO_DEFAULT_HOME` set the default
  `~/.local/share/deliberator` is never used: throws `:store/no-default-home` instead."
  ^Path []
  (let [h (or *home*
              (System/getenv "DELIBERATOR_HOME")
              (if (System/getenv "DELIBERATOR_NO_DEFAULT_HOME")
                (throw (ex-info no-default-home-message {:type :store/no-default-home}))
                (str (System/getProperty "user.home") "/.local/share/deliberator")))]
    (.toAbsolutePath (.normalize (.toPath (io/file (str h)))))))

(def max-name-length "Longest workspace name, session key or run id." 100)

(defn safe-name
  "Validates a workspace name or session key (no path separators, no leading dot); returns it as
  a string."
  [x]
  (let [s (if (keyword? x) (name x) (str x))]
    (when-not (and (re-matches #"[A-Za-z0-9_][A-Za-z0-9._-]*" s) (<= (count s) max-name-length))
      (throw (ex-info (str "invalid name: " (pr-str (if (> (count s) 80) (str (subs s 0 80) "...") x)))
                      (cond-> {:type :store/invalid-name :name (if (> (count s) 80) (subs s 0 80) x)}
                        (> (count s) max-name-length) (assoc :hint (str "at most " max-name-length " characters"))))))
    s))

(defn ws-name [ws]
  (safe-name (if (map? ws) (:name ws) ws)))

(defn container-name
  "The runtime container name of `role` (a role label such as \"floci\" or \"service-a\") in workspace
  `ws`: `deliberator-<ws>-<role>`, so two workspaces on one host never collide by name."
  [ws role]
  (str "deliberator-" (ws-name ws) "-" role))

(defn path
  "`<home>/workspaces/<ws>/parts...` as a Path."
  ^Path [ws & parts]
  (reduce (fn [^Path p part] (.resolve p (str part)))
          (.resolve (home) (str "workspaces/" (ws-name ws)))
          parts))

;; atomic EDN ---------------------------------------------------------------------------------

(defn- ->path ^Path [p] (if (instance? Path p) p (.toPath (io/file (str p)))))

(defn- fsync! [^Path p]
  (with-open [ch (FileChannel/open p (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
    (.force ch true)))

(defn write-bytes-atomic!
  "Writes `bytes` to `target` through a sibling temp file and an atomic rename. Parent directories
  are created. Returns the target Path."
  [target ^bytes bytes]
  (let [target (->path target)
        dir (.toAbsolutePath (.getParent (.toAbsolutePath target)))
        tmp (.resolve dir (str "." (.getFileName target) "." (subs (str (UUID/randomUUID)) 0 8) ".tmp"))]
    (Files/createDirectories dir (make-array FileAttribute 0))
    (Files/write tmp bytes (make-array java.nio.file.OpenOption 0))
    (fsync! tmp)
    (when *before-rename* (*before-rename* tmp))
    (try
      (Files/move tmp target (into-array java.nio.file.CopyOption
                                         [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        (Files/move tmp target (into-array java.nio.file.CopyOption
                                           [StandardCopyOption/REPLACE_EXISTING]))))
    target))

(defn write-edn!
  "Atomically writes `data` as pretty EDN to `target`. Returns the target Path."
  [target data]
  (let [s (binding [*print-namespace-maps* false]
            (with-out-str
              (pp/pprint (walk/postwalk #(if (instance? Instant %)
                                           (tagged-literal 'inst (str %))
                                           %)
                                        data))))]
    (write-bytes-atomic! target (.getBytes ^String s "UTF-8"))))

(defn read-edn
  "Reads the EDN file at `p`; nil when it does not exist."
  [p]
  (let [f (->path p)]
    (when (Files/exists f (make-array LinkOption 0))
      (edn/read-string {:readers {'inst #(Instant/parse %)}} (slurp (.toFile f))))))

;; raw store ----------------------------------------------------------------------------------

(defn- hex [^bytes b]
  (let [sb (StringBuilder.)]
    (doseq [x b] (.append sb (format "%02x" (bit-and x 0xff))))
    (str sb)))

(defn sha256 [^bytes bytes]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn- check-sha [sha]
  (when-not (re-matches #"[0-9a-f]{64}" (str sha))
    (throw (ex-info (str "invalid sha: " (pr-str sha)) {:type :store/invalid-sha :sha sha}))))

(defn raw-path
  "Path of the blob with `sha` (it may not exist)."
  ^Path [ws sha]
  (check-sha sha)
  (path ws "raw" "sha256" (subs sha 0 2) (subs sha 2)))

(defn- meta-path ^Path [ws sha]
  (let [p (raw-path ws sha)]
    (.resolveSibling p (str (.getFileName p) ".meta.edn"))))

(defn- exists? [^Path p] (Files/exists p (make-array LinkOption 0)))

(defn- meta-problems [m]
  (cond-> []
    (not (map? m)) (conj "meta must be a map")
    (and (map? m) (not (contains? sources (:source m))))
    (conj (str ":source must be one of " (sort sources)))
    (and (map? m) (nil? (:op m))) (conj ":op is required")
    (and (map? m) (not (contains? m :env))) (conj ":env key is required (nil allowed)")
    (and (map? m) (not (instance? Instant (:at m)))) (conj ":at must be an Instant")
    (and (map? m) (nil? (:content-type m))) (conj ":content-type is required")))

(defn raw-put!
  "Persists `bytes` (byte array) with `meta` content-addressed. Returns
  `{:sha :path :bytes n :new? bool}`; `:new?` is false when the blob already existed (its first meta
  is kept; a missing sidecar from an interrupted put is repaired)."
  [ws ^bytes bytes meta]
  (let [problems (meta-problems meta)]
    (when (seq problems)
      (throw (ex-info (str "invalid raw meta: " (pr-str problems))
                      {:type :store/invalid-meta :problems problems}))))
  (let [sha (sha256 bytes)
        p (raw-path ws sha)
        mp (meta-path ws sha)
        new? (not (exists? p))]
    (when new?
      (write-bytes-atomic! p bytes)
      (.setWritable (.toFile p) false false))
    (when-not (exists? mp)
      (write-edn! mp (assoc meta :bytes (alength bytes) :sha sha)))
    {:sha sha :path p :bytes (alength bytes) :new? new?}))

(defn raw-adopt!
  "Like `raw-put!` for a finished file that must not be read into memory (a followed log of any length): the sha-256 is
  streamed, the file is made read-only and the blob becomes a HARD LINK to it (one inode under two names; a copy when
  the filesystem refuses the link). When the blob already exists nothing is linked and `file` stays what it is.
  -> `{:sha :path :bytes :new?}` as `raw-put!`."
  [ws file meta]
  (let [problems (meta-problems meta)
        f (->path file)]
    (when (seq problems)
      (throw (ex-info (str "invalid raw meta: " (pr-str problems)) {:type :store/invalid-meta :problems problems})))
    (let [md (MessageDigest/getInstance "SHA-256")
          n (with-open [in (Files/newInputStream f (make-array java.nio.file.OpenOption 0))]
              (let [buf (byte-array 65536)]
                (loop [total 0]
                  (let [k (.read in buf)]
                    (if (neg? k) total (do (.update md buf 0 k) (recur (+ total k))))))))
          sha (hex (.digest md))
          p (raw-path ws sha)
          mp (meta-path ws sha)
          new? (not (exists? p))]
      (when new?
        (.setWritable (.toFile f) false false)
        (Files/createDirectories (.getParent p) (make-array FileAttribute 0))
        (try (Files/createLink p f)
             (catch java.io.IOException _
               (let [tmp (.resolve (.getParent p) (str "." (.getFileName p) ".adopt.tmp"))]
                 (Files/copy f tmp (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
                 (fsync! tmp)
                 (Files/move tmp p (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                 (.setWritable (.toFile p) false false)))))
      (when-not (exists? mp)
        (write-edn! mp (assoc meta :bytes n :sha sha)))
      {:sha sha :path p :bytes n :new? new?})))

(defn raw-get
  "The bytes of blob `sha`; throws `:store/not-found` when absent."
  ^bytes [ws sha]
  (let [p (raw-path ws sha)]
    (if (exists? p)
      (Files/readAllBytes p)
      (throw (ex-info (str "no such blob " sha) {:type :store/not-found :sha sha})))))

(defn raw-meta
  "The sidecar map of blob `sha`, nil when absent."
  [ws sha]
  (read-edn (meta-path ws sha)))

(defn link!
  "Hard-links blob `sha` to `target-path` (parents created); copies when linking fails (other
  filesystem). Idempotent when the target already holds the same bytes, otherwise throws.
  Returns `{:path target :linked? bool}`."
  [ws sha target-path]
  (let [src (raw-path ws sha)
        target (.toAbsolutePath (->path target-path))]
    (when-not (exists? src)
      (throw (ex-info (str "no such blob " sha) {:type :store/not-found :sha sha})))
    (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
    (try
      (Files/createLink target src)
      {:path target :linked? true}
      (catch FileAlreadyExistsException _
        (if (= sha (sha256 (Files/readAllBytes target)))
          {:path target :linked? (Files/isSameFile target src)}
          (throw (ex-info "link target exists with different content"
                          {:type :store/target-exists :target (str target)}))))
      (catch java.io.IOException _
        (Files/copy src target (into-array java.nio.file.CopyOption []))
        {:path target :linked? false}))))
