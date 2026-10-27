(ns deliberator.fake.scrub
  "Scrubbing recorded exchanges by rule before `fake.commit` copies them into a repository. The raw recording stays
  untouched under the session (principle 1); only the copy is scrubbed. Rules work on the text of an exchange, never on its structure,
  so the bodies keep their formatting and key order:

    headers   authorization, proxy-authorization, cookie, set-cookie, x-api-key, x-auth-token, x-amz-security-token, x-csrf-token,
              x-xsrf-token, and any header whose name contains token, secret, api-key, password or credential -> `<scrubbed>`
    tokens    in bodies, paths and queries: JSON string values (`\"password\": \"..\"`) and `key=value` pairs whose key contains token,
              secret, password, passwd, api-key, credential or authorization -> `<scrubbed>`; JWTs -> `<jwt>`; `Bearer ..` ->
              `Bearer <scrubbed>`; AWS access key ids (AKIA..) -> `<aws-key>`
    e-mails   -> `user-N@example.invalid` (the same address always becomes the same N inside one commit, so correlations survive)
    names     JSON string values of first/last/full/given/family/middle/sur/display/customer/owner/contact/user name keys -> `Name-N`
              (stable like the e-mails). A bare `name` key is NOT scrubbed by default (service names, resource names): add
              `:scrub {:name-keys [\"name\"]}` to the fake's declaration when it holds people.

  The fake's declaration (`fakes/<name>.edn`) extends the rules: `:scrub {:headers [\"x-foo\"] :json-keys [\"pin\"] :name-keys [\"name\"]
  :patterns [{:re \"ACME-\\\\d+\" :with \"ACME-0\"}]}`. Binary (base64) bodies are not scanned and are counted as such in the report.
  Report: rule -> how many replacements; never a value."
  (:require [clojure.string :as str]))

(def scrubbed "<scrubbed>")

(def ^:private default-headers
  #{"authorization" "proxy-authorization" "cookie" "set-cookie" "x-api-key" "x-auth-token" "x-amz-security-token" "x-csrf-token" "x-xsrf-token"})

(def ^:private header-re #"(?i)token|secret|api-?key|password|credential")

(def ^:private token-key "[\\w-]*(?:token|secret|password|passwd|api[-_]?key|credential|authorization)[\\w-]*")
(def ^:private name-key "(?:first|last|full|given|family|middle|sur|display|customer|owner|contact|user)[-_]?name")

(def ^:private email-re #"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
(def ^:private jwt-re #"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*")
(def ^:private bearer-re #"(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]{8,}")
(def ^:private aws-key-re #"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")

(defn- alt [defaults extras]
  (str/join "|" (concat [defaults] (map #(java.util.regex.Pattern/quote (str %)) extras))))

(defn scrubber
  "A scrubber for the declaration's `:scrub` map (nil = the defaults): `{:counts atom :emails atom :names atom :headers set :header-re .. :json-token-re ..}`."
  [rules]
  (let [json-keys (alt token-key (:json-keys rules))
        name-keys (alt name-key (:name-keys rules))]
    {:counts (atom (sorted-map)) :emails (atom {}) :names (atom {})
     :headers (into default-headers (map str/lower-case) (:headers rules))
     :patterns (vec (for [{:keys [re with]} (:patterns rules)] [(re-pattern re) (or with scrubbed)]))
     ;; "key": "value"  (JSON string values)
     :json-token (re-pattern (str "(?i)(\"(?:" json-keys ")\"\\s*:\\s*)\"((?:[^\"\\\\]|\\\\.)*)\""))
     :json-name (re-pattern (str "(?i)(\"(?:" name-keys ")\"\\s*:\\s*)\"((?:[^\"\\\\]|\\\\.)*)\""))
     ;; key=value  (queries, form bodies)
     :form-token (re-pattern (str "(?i)((?:^|[?&;\\s])(?:" json-keys ")=)([^&\\s]+)"))}))

(defn- bump! [sc rule n] (when (pos? n) (swap! (:counts sc) update rule (fnil + 0) n)))

(defn- replace-counting
  "`(str/replace s re f)` counting the matches under `rule`."
  [sc rule ^String s re f]
  (let [n (atom 0)
        out (str/replace s re (fn [m] (swap! n inc) (f m)))]
    (bump! sc rule @n)
    out))

(defn- pseudonym! [sc kind raw fmt]
  (let [a (get sc kind) k (if (= kind :emails) (str/lower-case raw) raw)]
    (get (swap! a (fn [m] (if (contains? m k) m (assoc m k (format fmt (inc (count m))))))) k)))

(defn scrub-text
  "A text (path, query, body, header value) with every rule applied."
  [sc s]
  (if (or (nil? s) (not (string? s)) (str/blank? s))
    s
    (-> s
        (as-> t (replace-counting sc "token:json" t (:json-token sc) (fn [[_ k _]] (str k "\"" scrubbed "\""))))
        (as-> t (replace-counting sc "name" t (:json-name sc) (fn [[_ k v]] (str k "\"" (pseudonym! sc :names v "Name-%d") "\""))))
        (as-> t (replace-counting sc "token:form" t (:form-token sc) (fn [[_ k _]] (str k scrubbed))))
        (as-> t (replace-counting sc "jwt" t jwt-re (fn [_] "<jwt>")))
        (as-> t (replace-counting sc "bearer" t bearer-re (fn [[_ b]] (str b scrubbed))))
        (as-> t (replace-counting sc "aws-key" t aws-key-re (fn [_] "<aws-key>")))
        (as-> t (replace-counting sc "email" t email-re (fn [[e]] (pseudonym! sc :emails e "user-%d@example.invalid"))))
        (as-> t (reduce (fn [t [re with]] (replace-counting sc "pattern" t re (fn [_] with))) t (:patterns sc))))))

(defn- scrub-headers [sc headers]
  (into (sorted-map)
        (for [[k v] headers
              :let [lk (str/lower-case (name k))
                    secret? (or (contains? (:headers sc) lk) (re-find header-re lk))]]
          [(name k) (cond secret? (do (bump! sc (str "header:" lk) 1) scrubbed)
                          (string? v) (scrub-text sc v)
                          (sequential? v) (mapv #(scrub-text sc %) v)
                          :else v)])))

(defn- scrub-body [sc part]
  (cond-> part
    (contains? part :body) (update :body #(scrub-text sc %))
    (contains? part :body-base64) (as-> p (do (bump! sc "binary-body-not-scanned" 1) p))))

(defn scrub-exchange
  "One parsed exchange file (keyword keys) -> the scrubbed one."
  [sc ex]
  (-> ex
      (update-in [:request :path] #(scrub-text sc %))
      (update-in [:request :query] #(scrub-text sc %))
      (update-in [:request :headers] #(scrub-headers sc %))
      (update :request #(scrub-body sc %))
      (update-in [:response :headers] #(scrub-headers sc %))
      (update :response #(scrub-body sc %))
      (update-in [:match :path-template] #(scrub-text sc %))))

(defn report
  "rule -> replacements so far (a sorted map)."
  [sc] @(:counts sc))
