(ns deliberator.tunnel
  "The `ssh://` Podman endpoint: `ssh://[user@]host[:port]/abs/path/podman.sock` in `runtime.edn :podman :endpoint`.
  The core opens ONE tunnel per daemon per workspace and endpoint through the `ssh` binary,

      ssh -N -L <ws>/tunnel/p-<hash>.sock:<remote socket> -o ExitOnForwardFailure=yes -o BatchMode=yes
          -o StreamLocalBindUnlink=yes -o ServerAliveInterval=15 [-p PORT] <:ssh :options> [user@]host

  and the Podman client speaks `unix://` to the local end (`deliberator.runtime/podman-client`). Nothing overrides
  `ControlMaster`/`ControlPath`: the user's ssh config applies (a mux master is reused). `runtime.edn :podman :ssh`
  `{:bin \"ssh\" :options [\"-i\" \"key\" ...]}` names another binary or extra arguments.

  Everything ssh prints goes to `<ws>/tunnel/ssh.log` (persist first). A tunnel that cannot be opened, or whose process died,
  is `podman/tunnel-failed` with the exit code and the tail of that log; the dead entry is dropped so the next call tries
  again. A local socket path over 103 bytes (the unix limit) is `podman/tunnel-path-too-long`. The tunnels are closed by
  `close-all!` (the daemon calls it from `stop!`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.store :as store])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels SocketChannel)
           (java.nio.file Files)
           (java.util.concurrent TimeUnit)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn parse-ssh
  "`ssh://[user@]host[:port]/abs/remote/socket` -> `{:endpoint :user :host :port :remote}`; `:podman/unsupported-endpoint`
  when it is not of that form."
  [endpoint]
  (let [[_ user host port remote] (re-matches #"ssh://(?:([^@/\s]+)@)?([^:/@\s]+)(?::(\d+))?(/\S+)" (str endpoint))]
    (when-not host
      (fail :podman/unsupported-endpoint (str "ssh endpoint must look like ssh://user@host[:port]/run/user/1000/podman/podman.sock: " (pr-str endpoint))
            :endpoint endpoint))
    (cond-> {:endpoint endpoint :host host :remote remote}
      user (assoc :user user)
      port (assoc :port (Long/parseLong port)))))

(defn target [{:keys [user host]}] (if user (str user "@" host) host))

(defn socket-file
  "The local end of the tunnel of `endpoint` in `ws`: `<ws>/tunnel/p-<hash>.sock`."
  [ws endpoint]
  (let [f (.toFile (store/path ws "tunnel" (str "p-" (subs (store/sha256 (.getBytes (str endpoint) "UTF-8")) 0 8) ".sock")))]
    (when (> (count (.getBytes (str f) "UTF-8")) 103)
      (fail :podman/tunnel-path-too-long (str "the tunnel socket path " f " is longer than a unix socket path may be (103 bytes)")
            :path (str f) :hint "use a shorter DELIBERATOR_HOME"))
    f))

(defn ssh-args
  "The full command line (vector) for endpoint `ep` (`parse-ssh`), local socket path `local`, `{:bin :options}`."
  [ep local {:keys [bin options]}]
  (-> [(or bin "ssh") "-N" "-L" (str local ":" (:remote ep))
       "-o" "ExitOnForwardFailure=yes" "-o" "BatchMode=yes" "-o" "StreamLocalBindUnlink=yes"
       "-o" "ServerAliveInterval=15"]
      (into (when (:port ep) ["-p" (str (:port ep))]))
      (into (map str options))
      (conj (target ep))))

;; registry -------------------------------------------------------------------------------------

(defonce ^:private registry (atom {}))   ; [tunnel dir, endpoint] -> {:process :socket :log :cmd :started}

(defn- reg-key [ws endpoint] [(str (store/path ws "tunnel")) (str endpoint)])

(defn- log-tail [^java.io.File f]
  (if (.isFile f)
    (let [ls (str/split-lines (slurp f))] (str/join "\n" (take-last 15 ls)))
    ""))

(defn- accepts? [^java.io.File sock]
  (and (.exists sock)
       (try (with-open [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
              (.connect ch (UnixDomainSocketAddress/of (.toPath sock))) true)
            (catch java.io.IOException _ false))))

(defn- alive? [{:keys [^Process process]}] (and process (.isAlive process)))

(defn- tail-since
  "The ssh log from byte offset `from`."
  [^java.io.File f from]
  (if (.isFile f)
    (let [s (slurp f)] (str/trim (subs s (min (count s) from))))
    ""))

(defn- failed [ep cmd exit output]
  (fail :podman/tunnel-failed
        (str "the ssh tunnel to " (target ep) " is not up" (when exit (str " (ssh exited " exit ")")) ": "
             (if (str/blank? output) "ssh printed nothing" output))
        :endpoint (:endpoint ep) :exit exit :output output :command (str/join " " cmd)
        :hint (str "run it by hand: " (str/join " " cmd))))

(defn- open! [ws ep opts]
  (let [sock (socket-file ws (:endpoint ep))
        logf (io/file (.getParentFile sock) "ssh.log")
        _ (io/make-parents sock)
        _ (Files/deleteIfExists (.toPath sock))
        cmd (ssh-args ep (str sock) opts)
        offset (if (.isFile logf) (count (slurp logf)) 0)
        _ (spit logf (str "# " (store/now) " " (str/join " " cmd) "\n") :append true)
        offset (+ offset 0)
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream true)
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo logf)))
        proc (try (.start pb)
                  (catch java.io.IOException e
                    (failed ep cmd nil (str "cannot run " (first cmd) ": " (.getMessage e)))))
        deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (cond
        (not (.isAlive proc))
        (do (Thread/sleep 50)
            (failed ep cmd (.exitValue proc) (tail-since logf offset)))
        (accepts? sock) {:process proc :socket sock :log logf :cmd cmd :started (store/now)}
        (> (System/currentTimeMillis) deadline)
        (do (.destroyForcibly proc)
            (failed ep cmd nil (str "no local socket after 15 s. " (tail-since logf offset))))
        :else (do (Thread/sleep 50) (recur))))))

(defn ensure!
  "The local socket path (string) of the tunnel for `endpoint` in `ws`, opened when there is none. One per
  daemon per workspace and endpoint. A dead tunnel is dropped and reported as `podman/tunnel-failed`."
  [ws endpoint opts]
  (let [ep (parse-ssh endpoint)
        k (reg-key ws endpoint)]
    (locking registry
      (let [cur (get @registry k)]
        (cond
          (and cur (alive? cur)) (str (:socket cur))
          cur (do (swap! registry dissoc k)
                  (failed ep (:cmd cur) (.exitValue ^Process (:process cur)) (log-tail (:log cur))))
          :else (let [t (open! ws ep opts)]
                  (swap! registry assoc k t)
                  (str (:socket t))))))))

(defn status
  "Never throws: `{:state :up|:dead|:down :ssh target :socket :pid :log [:exit :output]}`; `:down` = none opened yet."
  [ws endpoint]
  (let [ep (parse-ssh endpoint)
        cur (get @registry (reg-key ws endpoint))]
    (cond-> {:ssh (target ep) :remote (:remote ep)
             :state (cond (nil? cur) :down (alive? cur) :up :else :dead)}
      cur (assoc :socket (str (:socket cur)) :log (str (:log cur)) :pid (.pid ^Process (:process cur))
                 :started (str (:started cur)))
      (and cur (not (alive? cur))) (assoc :exit (.exitValue ^Process (:process cur)) :output (log-tail (:log cur))))))

(defn- close-entry! [{:keys [^Process process ^java.io.File socket]}]
  (when process
    (.destroy process)
    (when-not (.waitFor process 2 TimeUnit/SECONDS) (.destroyForcibly process)))
  (when socket (try (Files/deleteIfExists (.toPath socket)) (catch Exception _ nil))))

(defn close!
  "Closes the tunnel of `endpoint` in `ws` (no-op when none). -> true when one was open."
  [ws endpoint]
  (let [k (reg-key ws endpoint)
        [old _] (swap-vals! registry dissoc k)]
    (boolean (when-let [t (get old k)] (close-entry! t) true))))

(defn close-all!
  "Closes every tunnel this JVM opened. -> the number closed."
  []
  (let [[old _] (reset-vals! registry {})]
    (doseq [t (vals old)] (try (close-entry! t) (catch Throwable _ nil)))
    (count old)))
