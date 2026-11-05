#!/usr/bin/env bb
;; The model of the tests: answers canned text, never calls a real model. Run by `scenario.generate` like the real command:
;; the prompt on stdin, the context folder as the working directory, the answer on stdout.
;;   FIXTURE_DIR      a folder with answer-1.txt, answer-2.txt ... (the Nth call gets the Nth, the last one repeats)
;;   FIXTURE_FORMAT   "claude-json" (default: the document `claude -p --output-format json` prints) | "text" | "garbage"
;; It writes seen-N.md (the prompt it got) and cwd-N.txt (its working directory and the files it could see) into FIXTURE_DIR.
(require '[babashka.fs :as fs] '[cheshire.core :as json] '[clojure.string :as str])
(let [dir (System/getenv "FIXTURE_DIR")
      fmt (or (System/getenv "FIXTURE_FORMAT") "claude-json")
      n (inc (count (fs/glob dir "seen-*.md")))
      prompt (slurp *in*)
      answers (sort (map str (fs/glob dir "answer-*.txt")))
      answer (slurp (or (nth answers (dec n) nil) (last answers)))
      cwd (System/getProperty "user.dir")]
  (spit (str (fs/path dir (str "seen-" n ".md"))) prompt)
  (spit (str (fs/path dir (str "cwd-" n ".txt")))
        (str cwd "\n" (str/join "\n" (sort (map #(str (fs/relativize cwd %)) (fs/glob cwd "**"))))))
  (case fmt
    "text" (print answer)
    "garbage" (print "this is not json <<<")
    (println (json/generate-string
              {:type "result" :subtype "success" :is_error false :result answer :session_id (str "fixture-session-" n)
               :num_turns 2 :duration_ms 1234 :total_cost_usd 0.0123
               :usage {:input_tokens 4 :cache_creation_input_tokens 1000 :cache_read_input_tokens 2000 :output_tokens (* 10 n)}
               :modelUsage {"fixture-haiku" {:inputTokens 4 :outputTokens (* 10 n)}}}))))
