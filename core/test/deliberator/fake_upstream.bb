;; the upstream of fake_test.clj: a tiny API in a babashka container
(require '[org.httpkit.server :as s] '[cheshire.core :as json] '[clojure.string :as str])
(s/run-server
 (fn [{:keys [uri request-method headers body]}]
   (let [b (some-> body slurp)]
     (println "UP" request-method uri (get headers "x-api-key"))
     (cond
       (= uri "/health") {:status 200 :body "ok"}
       (and (= request-method :post) (str/starts-with? uri "/ack/"))
       {:status 200 :headers {"content-type" "application/json" "set-cookie" "sid=abc; Path=/"}
        :body (json/generate-string {:status "ACKED" :id (subs uri 5) :ownerName "Jane Doe" :email "jane.doe@example.com" :echo b})}
       (str/starts-with? uri "/messages/") {:status 200 :headers {"content-type" "application/json"} :body (json/generate-string {:id (subs uri 10) :n 1})}
       :else {:status 404 :body "nope"})))
 {:port 8080 :ip "0.0.0.0"})
@(promise)
