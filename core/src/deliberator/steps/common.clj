(ns deliberator.steps.common
  "Helpers the plain kinds share: reading a step's own directives and bytes <-> text."
  (:require [deliberator.scenario.expr :as expr])
  (:import (java.nio.charset StandardCharsets)))

(defn dirs
  "Values of every directive `name` of the step, in order."
  [step name]
  (vec (keep #(when (= name (:name %)) (:value %)) (:directives step))))

(defn dir "The first value of directive `name`, or nil." [step name] (first (dirs step name)))

(defn duration-dir
  "Milliseconds of the duration directive `name`, `default-ms` when absent."
  [step name default-ms]
  (if-let [v (dir step name)] (expr/parse-duration v) default-ms))

(defn utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn text [^bytes bs] (String. bs StandardCharsets/UTF_8))

(defn env-name "The environment's name for hints, `?` when unknown." [ctx] (or (some-> ctx :env :name) "?"))
