(ns ijdev.hotswap
  "Bringing freshly compiled classes of a plugin into the running IDE, without unloading it.

  What the JVM runs is compared with what is compiled: the baseline is the plugin's own jars in the sandbox (what its class loader
  read), later the bytes this namespace swapped in. Changed classes that are loaded are redefined in one batch
  (Instrumentation.redefineClasses; on JBR with -XX:+AllowEnhancedClassRedefinition also added/removed methods and fields). Changed
  or new classes that are not loaded yet are defined into the plugin's class loader, superclasses first, through a private lookup on
  a loaded class of the same package, so they cannot later be read stale from the jar. A class with no loaded neighbour in its
  package is skipped and named: that one needs `ijdev.plugins/reload!`.

  Not covered by a swap: plugin.xml (extensions, actions, services registered), resources, and state already held by live objects
  (a service keeps its fields; a changed constructor runs only for new instances)."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ijdev.ide :as ide])
  (:import (java.io DataInputStream File)
           (java.lang.instrument ClassDefinition Instrumentation)
           (java.lang.invoke MethodHandles)
           (java.security MessageDigest)
           (java.util.zip ZipFile)))

(defn ^Instrumentation instrumentation []
  (or (try (.get (.getField (Class/forName "ijdev.agent.Agent" false (ClassLoader/getSystemClassLoader)) "instrumentation") nil)
           (catch ClassNotFoundException _ nil))
      (throw (ex-info "no Instrumentation: the IDE was not started with -javaagent:ijdev-agent.jar (use the runIdeDev task)" {}))))

(defn- sha ^String [^bytes b]
  (let [d (.digest (MessageDigest/getInstance "SHA-1") b)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- class-name [^String entry] (-> entry (subs 0 (- (count entry) 6)) (str/replace "/" ".")))

(defn compiled
  "class name -> File for every .class under dirs (later dirs win)."
  [dirs]
  (into {} (for [d dirs
                 :let [root (io/file d)]
                 :when (.isDirectory root)
                 ^File f (file-seq root)
                 :when (str/ends-with? (.getName f) ".class")]
             [(class-name (str/replace (subs (.getPath f) (inc (count (.getPath root)))) File/separator "/")) f])))

(defn- plugin-jars [plugin-id]
  (let [root (.toFile (.getPluginPath (ide/plugin plugin-id)))
        lib (io/file root "lib")]
    (->> (concat (when (.isDirectory lib) (.listFiles lib)) [root])
         (filter #(str/ends-with? (.getName ^File %) ".jar")))))

(defn jar-baseline
  "class name -> sha of the class bytes in the plugin's jars, restricted to `names`."
  [plugin-id names]
  (let [names (set names)]
    (into {} (for [^File j (plugin-jars plugin-id)
                   :let [z (ZipFile. j)]
                   e (try (doall (for [e (enumeration-seq (.entries z))
                                       :let [n (.getName ^java.util.zip.ZipEntry e)]
                                       :when (and (str/ends-with? n ".class") (names (class-name n)))]
                                   [(class-name n) (sha (.readAllBytes (.getInputStream z e)))]))
                          (finally (.close z)))]
               e))))

(defn- loaders-of [plugin-id]
  (let [p (ide/plugin plugin-id)]
    (set (cons (.getPluginClassLoader p)
               (when (instance? com.intellij.ide.plugins.PluginMainDescriptor p)
                 (keep #(.getPluginClassLoader ^com.intellij.ide.plugins.IdeaPluginDescriptor %)
                       (.getContentModules ^com.intellij.ide.plugins.PluginMainDescriptor p)))))))

(defn- header
  "{:super \"a.B\" :interfaces [...]} from class bytes (constant pool walk)."
  [^bytes b]
  (let [in (DataInputStream. (java.io.ByteArrayInputStream. b))
        _ (.readInt in) _ (.readUnsignedShort in) _ (.readUnsignedShort in)
        n (.readUnsignedShort in)
        cp (object-array n)]
    (loop [i 1]
      (when (< i n)
        (let [tag (.readUnsignedByte in)]
          (case (int tag)
            1 (aset cp i (.readUTF in))
            7 (aset cp i [:class (.readUnsignedShort in)])
            (8 16 19 20) (.readUnsignedShort in)
            15 (do (.readUnsignedByte in) (.readUnsignedShort in))
            (3 4 9 10 11 12 17 18) (.readInt in)
            (5 6) (.readLong in))
          (recur (if (#{5 6} tag) (+ i 2) (inc i))))))
    (let [cls (fn [idx] (when (pos? idx) (str/replace (aget cp (second (aget cp idx))) "/" ".")))
          _ (.readUnsignedShort in) _ (.readUnsignedShort in)
          sup (cls (.readUnsignedShort in))
          k (.readUnsignedShort in)]
      {:super sup :interfaces (vec (repeatedly k #(cls (.readUnsignedShort in))))})))

(defn- supers-first
  "Orders class names so that a class comes after its superclass/interfaces among `names`."
  [names bytes-of]
  (let [names (set names)
        deps (into {} (for [n names] [n (filter names (let [{:keys [super interfaces]} (header (bytes-of n))] (cons super interfaces)))]))]
    (loop [done [] seen #{} todo (sort names)]
      (if-let [n (first todo)]
        (let [pending (remove seen (deps n))]
          (cond (seen n) (recur done seen (rest todo))
                (seq pending) (recur done seen (concat pending todo))
                :else (recur (conj done n) (conj seen n) (rest todo))))
        done))))

(defonce ^{:doc "plugin id -> {class name sha} of what this namespace swapped in"} swapped (atom {}))

(defn- package [^String n] (let [i (.lastIndexOf n ".")] (if (neg? i) "" (subs n 0 i))))

(defn swap!
  "Brings the changed classes of `dirs` (compiler output) into the running plugin `plugin`. Returns a summary map:
  :redefined / :defined (class names), :skipped {name reason}, :unchanged (count), :ms."
  [{:keys [plugin dirs]}]
  (let [t0 (System/currentTimeMillis)
        inst (instrumentation)
        files (compiled dirs)
        bytes-of (memoize (fn [n] (java.nio.file.Files/readAllBytes (.toPath ^File (files n)))))
        base (merge (jar-baseline plugin (keys files)) (get @swapped plugin))
        changed (filter #(not= (base %) (sha (bytes-of %))) (keys files))
        loaders (loaders-of plugin)
        loaded (->> (.getAllLoadedClasses inst)
                    (filter #(contains? loaders (.getClassLoader ^Class %)))
                    (group-by #(.getName ^Class %)))
        {to-redefine true to-define false} (group-by #(contains? loaded %) changed)
        anchor (fn [n] (->> loaded (filter (fn [[k _]] (= (package k) (package n)))) first second first))
        define-results (for [n (supers-first to-define bytes-of)]
                         (if-let [^Class a (anchor n)]
                           (try (.defineClass (MethodHandles/privateLookupIn a (MethodHandles/lookup)) (bytes-of n))
                                [:defined n]
                                (catch Throwable e [:skipped n (str (.getSimpleName (class e)) ": " (.getMessage e))]))
                           [:skipped n "nothing of its package is loaded yet: reload the plugin"]))
        define-results (doall define-results)
        redefine-error (when (seq to-redefine)
                         (try (.redefineClasses inst (into-array ClassDefinition
                                                                 (for [n to-redefine ^Class c (loaded n)]
                                                                   (ClassDefinition. c (bytes-of n)))))
                              nil
                              (catch Throwable e (str (.getName (class e)) ": " (.getMessage e)))))
        ok (concat (when-not redefine-error to-redefine) (for [[k n] define-results :when (= k :defined)] n))]
    (clojure.core/swap! swapped update plugin merge (into {} (for [n ok] [n (sha (bytes-of n))])))
    (cond-> {:redefined (if redefine-error [] (vec (sort to-redefine)))
             :defined (vec (for [[k n] define-results :when (= k :defined)] n))
             :skipped (into (sorted-map) (for [[k n why] define-results :when (= k :skipped)] [n why]))
             :unchanged (- (count files) (count changed))
             :ms (- (System/currentTimeMillis) t0)}
      redefine-error (assoc :redefine-failed {:classes (vec (sort to-redefine)) :error redefine-error}))))
