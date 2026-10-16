(ns deliberator.jdwp
  "A minimal JDWP client: `RedefineClasses` over the wire, for the plain-JRE reload path. It speaks
  the handshake, `VirtualMachine.IDSizes`, `ClassesBySignature` and `RedefineClasses` and nothing else. A JDWP port takes
  one client: while an IDE debugger holds it the connect is reset, which `redefine!` reports as `{:ok false :exception …}`
  (the caller restarts the service instead). Standard HotSwap only: a changed method set or field layout answers
  `:add-method-not-implemented` / `:schema-change-not-implemented`.

  `(redefine! host port root files)` connects, finds the loaded classes among `files` (class file paths relative to
  `root`, slash separated) and redefines them in one call. -> `{:ok :error-code :error :redefined [sig] :skipped [sig-files]
  :connect-ms :ms}` (`:skipped` = not loaded, nothing to redefine) or `{:ok false :exception msg}`."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream DataOutputStream)
           (java.net InetSocketAddress Socket)
           (java.nio.file Files Paths)))

(def errors {60 :invalid-class-format 61 :circular-class-definition 62 :fails-verification
             63 :add-method-not-implemented 64 :schema-change-not-implemented 65 :invalid-typestate
             66 :hierarchy-change-not-implemented 67 :delete-method-not-implemented 68 :unsupported-version
             69 :names-dont-match 70 :class-modifiers-change-not-implemented
             71 :method-modifiers-change-not-implemented 72 :class-attribute-change-not-implemented 99 :not-implemented})

(defn- send! [^DataOutputStream out id set cmd ^bytes data]
  (.writeInt out (+ 11 (alength data))) (.writeInt out id) (.writeByte out 0) (.writeByte out set) (.writeByte out cmd)
  (.write out data) (.flush out))

(defn- recv! [^DataInputStream in]
  (let [len (.readInt in) id (.readInt in) flags (.readByte in) err (.readUnsignedShort in)
        data (byte-array (- len 11))]
    (.readFully in data)
    {:id id :flags flags :error err :data (DataInputStream. (ByteArrayInputStream. data))}))

(defn- call [in out id set cmd ^bytes data]
  (send! out id set cmd data)
  (loop [] (let [r (recv! in)] (if (= (:id r) id) r (recur)))))

(defn- bytes-of [f] (let [b (ByteArrayOutputStream.) d (DataOutputStream. b)] (f d) (.toByteArray b)))

(defn- read-id [^DataInputStream in size]
  (case (int size) 8 (.readLong in) 4 (long (.readInt in))))

(defn- write-id [^DataOutputStream out size v]
  (case (int size) 8 (.writeLong out (long v)) 4 (.writeInt out (int v))))

(defn redefine!
  [host port root files]
  (try
    (let [t0 (System/nanoTime)
          sock (doto (Socket.) (.connect (InetSocketAddress. ^String host (int port)) 3000) (.setSoTimeout 15000))
          in (DataInputStream. (.getInputStream sock)) out (DataOutputStream. (.getOutputStream sock))]
      (try
        (.write out (.getBytes "JDWP-Handshake")) (.flush out)
        (.readFully in (byte-array 14))
        (let [connect-ms (quot (- (System/nanoTime) t0) 1000000)
              ids (:data (call in out 1 1 7 (byte-array 0)))
              _ (dotimes [_ 3] (.readInt ids))
              ref-size (.readInt ids)
              loaded (vec (for [f files
                                :let [sig (str "L" (str/replace f #"\.class$" "") ";")
                                      sb (.getBytes ^String sig "UTF-8")
                                      d (:data (call in out 2 1 2 (bytes-of #(do (.writeInt % (alength sb)) (.write % sb)))))
                                      n (.readInt d)]
                                :when (pos? n)]
                            (do (.readByte d) {:file f :sig sig :ref (read-id d ref-size)})))
              skipped (vec (remove (set (map :file loaded)) files))
              payload (bytes-of (fn [o] (.writeInt o (count loaded))
                                  (doseq [{:keys [file ref]} loaded
                                          :let [b (Files/readAllBytes (Paths/get root (into-array String [file])))]]
                                    (write-id o ref-size ref) (.writeInt o (alength b)) (.write o b))))
              r (if (seq loaded) (call in out 3 1 18 payload) {:error 0})]
          {:ok (zero? (:error r)) :error-code (:error r) :error (get errors (:error r))
           :redefined (mapv :sig loaded) :skipped skipped
           :connect-ms connect-ms :ms (quot (- (System/nanoTime) t0) 1000000)})
        (finally (.close sock))))
    (catch Exception e {:ok false :exception (str (.getSimpleName (class e)) ": " (.getMessage e))})))
