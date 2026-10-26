(ns deliberator.github-fake
  "A local stand-in for the GitHub REST API, for the cases a public repository cannot stage (a token echoed back, a rate limit,
  a job that is running and then completes, a blob on another host). `(start! handler)` serves on 127.0.0.1:<free port>;
  `handler` is `(fn [{:keys [path query headers]}])` -> `{:status :headers :body}` (body a string or bytes); every request is
  recorded in the returned map's `:requests` atom as `{:path :query :headers}` (header names lower-case). The API base is
  `(base s)`; a signed blob lives on `localhost` (another host name than the API's 127.0.0.1)."
  (:require [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(defn start! [handler]
  (let [reqs (atom [])
        srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              uri (.getRequestURI ex)
                              headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)])) (.getRequestHeaders ex))
                              req {:path (.getPath uri) :query (.getRawQuery uri) :headers headers}
                              _ (swap! reqs conj req)
                              {:keys [status headers body] :or {status 200}} (try (handler req) (catch Throwable t {:status 500 :body (str t)}))
                              ^bytes bs (cond (bytes? body) body :else (.getBytes (str body) "UTF-8"))]
                          (doseq [[k v] headers] (.add (.getResponseHeaders ex) k (str v)))
                          (.sendResponseHeaders ex status (if (zero? (alength bs)) -1 (alength bs)))
                          (when (pos? (alength bs)) (with-open [o (.getResponseBody ex)] (.write o bs)))
                          (.close ex)))))
    (.start srv)
    {:server srv :requests reqs :port (.getPort (.getAddress srv))}))

(defn stop! [{:keys [^HttpServer server]}] (.stop server 0))

(defn base [{:keys [port]}] (str "http://127.0.0.1:" port))
(defn blob-base [{:keys [port]}] (str "http://localhost:" port))
