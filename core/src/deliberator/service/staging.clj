(ns deliberator.service.staging
  "The staging dirs of a hotswap service and the reload state beside them. A staging dir is what the
  container sees at `/app/classes` (rw, watched by HotswapAgent): `<ws>/services/<name>/classes`, plus
  `classes-<module>` per sibling module. Maven rewrites every class file on each compile, so the dirs are brought
  up to date by a sha diff against the module's `target/classes` and only files whose bytes differ are copied (write to a
  temp name in the same directory, then an atomic rename; the agent re-creates a bean for every file it sees rewritten).

  `(sha-map dir)` rel path -> sha-256 of every regular file; `(diff seed staging)` -> `{:changed [rel] :removed [rel]}`
  (a file new in the seed counts as changed); `(sync! seed staging)` applies it and returns the diff;
  `(read-state ws name)` / `(write-state! ws name m)` the per-service `reload.edn` (`:container` id, `:count` of in-place
  reloads, `:at`, `:took-ms`, `:changed`, `:fallback`, and for the plain-JRE path `:jar-stamp` + `:redefined {rel sha}`)."
  (:require [clojure.java.io :as io]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.nio.file Files LinkOption Path StandardCopyOption)
           (java.security MessageDigest)))

(def tmp-suffix ".dlbtmp")

(defn dir
  "`<ws>/services/<name>/` as a File."
  ^File [ws name & parts]
  (.toFile ^Path (apply store/path ws "services" (store/safe-name name) parts)))

(defn staging-dir
  "The staging dir of `module` (nil = the service's own module) of service `name`."
  ^File [ws name module]
  (dir ws name (if module (str "classes-" module) "classes")))

(defn- sha-of [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream f)]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn sha-map
  "rel path (slash separated) -> sha-256 of every regular file under `dir`; {} when it does not exist."
  [^File dir]
  (if (.isDirectory dir)
    (let [root (.toPath dir)]
      (into (sorted-map)
            (for [^File f (file-seq dir)
                  :when (and (.isFile f) (not (.endsWith (.getName f) ^String tmp-suffix)))]
              [(str (.relativize root (.toPath f))) (sha-of f)])))
    {}))

(defn diff-maps
  "`{:changed [rel] :removed [rel]}` of seed shas against staged shas."
  [seed staged]
  {:changed (vec (for [[k v] seed :when (not= v (get staged k))] k))
   :removed (vec (for [k (keys staged) :when (not (contains? seed k))] k))})

(defn diff [^File seed ^File staging]
  (diff-maps (sha-map seed) (sha-map staging)))

(defn- copy-atomic! [^File from ^File to]
  (io/make-parents to)
  (let [tmp (io/file (.getParentFile to) (str (.getName to) tmp-suffix))]
    (Files/copy (.toPath from) (.toPath tmp) (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (Files/move (.toPath tmp) (.toPath to) (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn sync!
  "Brings `staging` to the content of `seed` (changed and new files copied, vanished files deleted). `seed` must exist
  (an unbuilt module would wipe the dir). -> the applied diff."
  [^File seed ^File staging]
  (when-not (.isDirectory seed)
    (throw (ex-info (str "nothing built at " seed) {:type :service/not-built :dir (str seed) :hint "dlb service build <name>"})))
  (.mkdirs staging)
  (let [d (diff seed staging)]
    (doseq [rel (:changed d)] (copy-atomic! (io/file seed rel) (io/file staging rel)))
    (doseq [rel (:removed d)] (Files/deleteIfExists (.toPath (io/file staging rel))))
    d))

(defn state-path [ws name] (.toPath (dir ws name "reload.edn")))
(defn read-state [ws name] (store/read-edn (state-path ws name)))
(defn write-state! [ws name m] (store/write-edn! (state-path ws name) m))
