(ns deliberator.tunnel-test
  "The `ssh://` Podman endpoint. Unit tests: the URL, the ssh command line, the lifecycle of a tunnel with a FAKE
  `ssh` (a script that honours `-L local:remote` with `socat`; skipped without socat): one tunnel per endpoint, a client
  through it, close, a failing ssh (typed error with its output), a tunnel that dies, close on `daemon.stop`.
  Integration: a REAL `sshd` the test starts itself as this user on a free port (host key, client key, authorized_keys and
  known_hosts under the test home, nothing of `~/.ssh`), forwarding the user's real Podman socket; `runtime/status` reports the
  tunnel and `podman/available?` answers through it. Skipped, with the reason printed, without sshd, ssh-keygen, socat or Podman."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.daemon :as daemon]
            [deliberator.floci-util :as fu]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]
            [deliberator.tunnel :as tunnel])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels ServerSocketChannel SocketChannel)
           (java.nio.file Files)))

(defn- exe? [bin] (zero? (:exit (try (let [p (.start (ProcessBuilder. ^java.util.List ["sh" "-c" (str "command -v " bin)]))] {:exit (.waitFor p)})
                                     (catch Exception _ {:exit 1})))))

(defn- write-exec! [^java.io.File f ^String s]
  (spit f s) (.setExecutable f true) f)

(def ^:private fake-ssh-script
  "#!/bin/bash
echo \"$@\" > \"$(dirname \"$0\")/argv.txt\"
while [ $# -gt 0 ]; do case \"$1\" in -L) spec=\"$2\"; shift 2;; *) shift;; esac; done
local=\"${spec%%:*}\"; remote=\"${spec#*:}\"
exec socat UNIX-LISTEN:\"$local\",fork UNIX-CONNECT:\"$remote\"
")

(def ^:private failing-ssh-script
  "#!/bin/bash
echo 'user@host: Permission denied (publickey).' >&2
exit 255
")

(defn- remote-server!
  "A unix socket at `path` answering every connection with `pong` (a stand-in for Podman's socket on the far side)."
  [^java.io.File path]
  (Files/deleteIfExists (.toPath path))
  (let [ch (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX) (.bind (UnixDomainSocketAddress/of (.toPath path))))
        t (Thread. ^Runnable (fn [] (try (loop [] (with-open [c (.accept ch)] (.write c (ByteBuffer/wrap (.getBytes "pong\n")))) (recur))
                                         (catch Exception _ nil))))]
    (.setDaemon t true) (.start t)
    #(do (.close ch) (Files/deleteIfExists (.toPath path)))))

(defn- ask [sock]
  (with-open [c (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect c (UnixDomainSocketAddress/of (.toPath (io/file sock))))
    (let [b (ByteBuffer/allocate 16)] (.read c b) (String. (.array b) 0 (.position b)))))

(defn- pid-alive? [pid] (.isPresent (java.lang.ProcessHandle/of pid)))
(defn- wait-dead [pid] (loop [n 40] (cond (not (pid-alive? pid)) true (zero? n) false :else (do (Thread/sleep 50) (recur (dec n))))))

(deftest ssh-urls
  (is (= {:endpoint "ssh://user@mac.local:2222/run/user/1000/podman/podman.sock" :user "user" :host "mac.local" :port 2222
          :remote "/run/user/1000/podman/podman.sock"}
         (tunnel/parse-ssh "ssh://user@mac.local:2222/run/user/1000/podman/podman.sock")))
  (is (= {:endpoint "ssh://host/p.sock" :host "host" :remote "/p.sock"} (tunnel/parse-ssh "ssh://host/p.sock")))
  (is (= :ssh (:scheme (podman/parse-endpoint "ssh://u@h/s.sock"))))
  (doseq [bad ["ssh://" "ssh://host" "ssh://host:22" "ssh://u@/s" "ssh://h:xx/s" "ssh://u@h/"]]
    (is (= :podman/unsupported-endpoint (:type (ex-data (try (tunnel/parse-ssh bad) (catch clojure.lang.ExceptionInfo e e))))) bad))
  (testing "a client never speaks ssh itself"
    (is (= :podman/unsupported-endpoint (:type (ex-data (try (podman/client "ssh://u@h/s.sock") (catch clojure.lang.ExceptionInfo e e))))))))

(deftest ssh-command-line
  (let [ep (tunnel/parse-ssh "ssh://user@mac.local:2222/run/podman.sock")
        a (tunnel/ssh-args ep "/h/t.sock" {:options ["-i" "/k" "-o" "IdentitiesOnly=yes"]})]
    (is (= "ssh" (first a)))
    (is (= "user@mac.local" (last a)))
    (is (= ["-N" "-L" "/h/t.sock:/run/podman.sock"] (subvec a 1 4)))
    (is (some #{"ExitOnForwardFailure=yes"} a))
    (is (some #{"BatchMode=yes"} a))
    (is (= ["-p" "2222"] (take 2 (drop-while #(not= "-p" %) a))))
    (is (every? (set a) ["-i" "/k" "IdentitiesOnly=yes"]))
    (testing "no override of the user's ControlMaster/ControlPath"
      (is (not-any? #(str/includes? % "Control") a)))
    (is (= "/bin/myssh" (first (tunnel/ssh-args ep "/h/t.sock" {:bin "/bin/myssh"}))))))

(deftest socket-path-limit
  (tu/with-temp-home
    (is (= :podman/tunnel-path-too-long
           (:type (ex-data (try (tunnel/socket-file (apply str "w" (repeat 90 "x")) "ssh://u@h/s") (catch clojure.lang.ExceptionInfo e e))))))))

(deftest tunnel-lifecycle-with-a-fake-ssh
  (if-not (exe? "socat")
    (fu/skip! (fu/current-test) "no socat")
    (tu/with-short-home
      (let [dir (.toFile (store/path "w" "t")) _ (.mkdirs dir)
            fake (write-exec! (io/file dir "fake-ssh") fake-ssh-script)
            remote (io/file dir "remote.sock")
            close-remote (remote-server! remote)
            endpoint (str "ssh://u@h" (.getAbsolutePath remote))
            opts {:bin (str fake)}]
        (try
          (testing "nothing opened yet"
            (is (= :down (:state (tunnel/status "w" endpoint)))))
          (let [sock (tunnel/ensure! "w" endpoint opts)
                st (tunnel/status "w" endpoint)]
            (testing "the tunnel is up and carries a client to the far side"
              (is (= :up (:state st)))
              (is (= "pong\n" (ask sock)))
              (is (str/starts-with? (slurp (io/file dir "argv.txt")) "-N -L ")))
            (testing "one tunnel per endpoint: the second call is the same process"
              (is (= sock (tunnel/ensure! "w" endpoint opts)))
              (is (= (:pid st) (:pid (tunnel/status "w" endpoint)))))
            (testing "ssh's output goes to a file under the workspace"
              (is (str/includes? (slurp (:log st)) "fake-ssh")))
            (testing "close ends the process and removes the socket"
              (is (true? (tunnel/close! "w" endpoint)))
              (is (wait-dead (:pid st)))
              (is (not (.exists (io/file sock))))
              (is (= :down (:state (tunnel/status "w" endpoint))))
              (is (false? (tunnel/close! "w" endpoint))))
            (testing "and a new call opens a new one"
              (let [s2 (tunnel/ensure! "w" endpoint opts)]
                (is (= "pong\n" (ask s2)))
                (testing "a tunnel that dies is reported once with ssh's output, then reopened"
                  (let [pid (:pid (tunnel/status "w" endpoint))]
                    (.destroyForcibly (.get (java.lang.ProcessHandle/of pid)))
                    (is (wait-dead pid))
                    (is (= :dead (:state (tunnel/status "w" endpoint))))
                    (let [e (ex-data (try (tunnel/ensure! "w" endpoint opts) nil (catch clojure.lang.ExceptionInfo e e)))]
                      (is (= :podman/tunnel-failed (:type e)))
                      (is (some? (:exit e))))
                    (is (= "pong\n" (ask (tunnel/ensure! "w" endpoint opts))) "the next call tries again"))))))
          (finally
            (tunnel/close-all!)
            (close-remote)))))))

(deftest a-failing-ssh-is-a-typed-error-with-its-output
  (tu/with-short-home
    (let [dir (.toFile (store/path "w" "t")) _ (.mkdirs dir)
          bad (write-exec! (io/file dir "bad-ssh") failing-ssh-script)
          endpoint "ssh://u@h/run/podman.sock"
          e (try (tunnel/ensure! "w" endpoint {:bin (str bad)}) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :podman/tunnel-failed (:type (ex-data e))))
      (is (= 255 (:exit (ex-data e))))
      (is (str/includes? (:output (ex-data e)) "Permission denied (publickey)"))
      (is (str/includes? (ex-message e) "Permission denied"))
      (is (str/includes? (slurp (io/file (.toFile (store/path "w" "tunnel")) "ssh.log")) "Permission denied") "persisted first")
      (testing "a missing binary is the same typed error"
        (is (= :podman/tunnel-failed
               (:type (ex-data (try (tunnel/ensure! "w" endpoint {:bin "/nonexistent/ssh"}) nil (catch clojure.lang.ExceptionInfo e e)))))))
      (testing "runtime.status does not throw: unreachable, with the tunnel and the ssh output"
        (store/write-edn! (runtime/config-path "w") {:podman {:endpoint endpoint :ssh {:bin (str bad)}} :network "n" :containers {}})
        (let [p (:podman (runtime/status "w"))]
          (is (false? (:reachable p)))
          (is (= :down (get-in p [:tunnel :state])))
          (is (str/includes? (get-in p [:tunnel :error]) "Permission denied")))))))

(deftest the-daemon-closes-its-tunnels-on-stop
  (if-not (exe? "socat")
    (fu/skip! (fu/current-test) "no socat")
    (tu/with-short-home
      (let [dir (.toFile (store/path "w" "t")) _ (.mkdirs dir)
            fake (write-exec! (io/file dir "fake-ssh") fake-ssh-script)
            remote (io/file dir "remote.sock")
            close-remote (remote-server! remote)
            endpoint (str "ssh://u@h" (.getAbsolutePath remote))
            h (daemon/start! {:idle-minutes 0})]
        (try
          (tunnel/ensure! "w" endpoint {:bin (str fake)})
          (let [pid (:pid (tunnel/status "w" endpoint))]
            (is (pid-alive? pid))
            ((:stop! h))
            (is (wait-dead pid) "daemon.stop closed the tunnel")
            (is (= :down (:state (tunnel/status "w" endpoint)))))
          (finally (tunnel/close-all!) (close-remote) ((:stop! h))))))))

(deftest runtime-reports-the-tunnel-and-speaks-through-it
  (if-not (and (exe? "socat") (podman/available? (podman/client (podman/default-endpoint))))
    (fu/skip! (fu/current-test) "no socat or no Podman socket")
    (tu/with-short-home
      (let [dir (.toFile (store/path "w" "t")) _ (.mkdirs dir)
            fake (write-exec! (io/file dir "fake-ssh") fake-ssh-script)
            real (str/replace (podman/default-endpoint) "unix://" "")
            endpoint (str "ssh://u@h" real)
            cfg {:podman {:endpoint endpoint :ssh {:bin (str fake)}} :network "n" :containers {}}]
        (try
          (store/write-edn! (runtime/config-path "w") cfg)
          (let [c (runtime/podman-client "w" cfg)]
            (is (str/starts-with? (:endpoint c) "unix://"))
            (is (not= (podman/default-endpoint) (:endpoint c)) "it speaks to the local end of the tunnel")
            (is (podman/available? c)))
          (let [p (:podman (runtime/status "w"))]
            (is (true? (:reachable p)))
            (is (= endpoint (:endpoint p)))
            (is (= :up (get-in p [:tunnel :state])))
            (is (= "u@h" (get-in p [:tunnel :ssh]))))
          (finally (tunnel/close-all!)))))))

;; a real sshd ---------------------------------------------------------------------------------

(defn- sh [& args]
  (let [p (-> (ProcessBuilder. ^java.util.List (vec args)) (.redirectErrorStream true) .start)
        out (slurp (.getInputStream p))]
    {:exit (.waitFor p) :out out}))

(defn- free-port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(deftest real-sshd-forwards-the-podman-socket
  (let [sshd (some #(when (.canExecute (io/file %)) %) ["/usr/bin/sshd" "/usr/sbin/sshd" "/usr/local/sbin/sshd"])]
    (cond
      (nil? sshd) (fu/skip! (fu/current-test) "no sshd binary")
      (not (exe? "ssh-keygen")) (fu/skip! (fu/current-test) "no ssh-keygen")
      (not (podman/available? (podman/client (podman/default-endpoint)))) (fu/skip! (fu/current-test) "no Podman socket to forward")
      :else
      (tu/with-short-home
        (let [dir (.toFile (store/path "w" "ssh")) _ (.mkdirs dir)
              f #(str (io/file dir %))
              port (free-port)
              user (System/getProperty "user.name")
              _ (is (zero? (:exit (sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" (f "host_key")))))
              _ (is (zero? (:exit (sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" (f "client_key")))))
              _ (spit (f "authorized_keys") (slurp (f "client_key.pub")))
              _ (spit (f "sshd_config")
                      (str/join "\n" [(str "Port " port) "ListenAddress 127.0.0.1" (str "HostKey " (f "host_key")) (str "PidFile " (f "sshd.pid"))
                                      (str "AuthorizedKeysFile " (f "authorized_keys")) "PasswordAuthentication no" "KbdInteractiveAuthentication no"
                                      "UsePAM no" "StrictModes no" "AllowStreamLocalForwarding yes" "AllowTcpForwarding yes" "LogLevel ERROR" ""]))
              proc (-> (ProcessBuilder. ^java.util.List [sshd "-D" "-e" "-f" (f "sshd_config")]) (.redirectErrorStream true)
                       (.redirectOutput (java.lang.ProcessBuilder$Redirect/to (io/file (f "sshd.log")))) .start)
              real (str/replace (podman/default-endpoint) "unix://" "")
              endpoint (str "ssh://" user "@127.0.0.1:" port real)
              cfg {:podman {:endpoint endpoint
                            :ssh {:options ["-F" "/dev/null" "-i" (f "client_key") "-o" "IdentitiesOnly=yes" "-o" "IdentityAgent=none"
                                            "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null" "-o" "LogLevel=ERROR"]}}
                   :network "n" :containers {}}]
          (try
            (Thread/sleep 800)
            (if-not (.isAlive proc)
              (fu/skip! (fu/current-test) (str "sshd could not start as this user: " (str/trim (slurp (f "sshd.log")))))
              (do
                (store/write-edn! (runtime/config-path "w") cfg)
                (testing "the client speaks Podman through the tunnel"
                  (let [c (runtime/podman-client "w" cfg)]
                    (is (podman/available? c))
                    (is (string? (get-in (podman/info c) [:version :Version])))))
                (testing "runtime.status reports the tunnel"
                  (let [p (:podman (runtime/status "w"))]
                    (is (true? (:reachable p)))
                    (is (= :up (get-in p [:tunnel :state])))
                    (is (= (str user "@127.0.0.1") (get-in p [:tunnel :ssh])))
                    (is (pos? (get-in p [:tunnel :pid])))))
                (testing "closing the tunnel ends the ssh process"
                  (let [pid (:pid (tunnel/status "w" endpoint))]
                    (tunnel/close-all!)
                    (is (wait-dead pid))))
                (testing "a wrong key: typed error carrying ssh's own words"
                  (let [bad (assoc-in cfg [:podman :ssh :options] ["-F" "/dev/null" "-i" "/nonexistent/key" "-o" "IdentitiesOnly=yes" "-o" "IdentityAgent=none"
                                                                   "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null" "-o" "LogLevel=ERROR"])
                        e (ex-data (try (runtime/podman-endpoint "w" bad) nil (catch clojure.lang.ExceptionInfo e e)))]
                    (is (= :podman/tunnel-failed (:type e)))
                    (is (str/includes? (:output e) "Permission denied"))))))
            (finally
              (tunnel/close-all!)
              (.destroy proc)
              (.waitFor proc 3 java.util.concurrent.TimeUnit/SECONDS))))))))
