;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns tokenscript-bench
  "Benchmark engine for `app.common.tokenscript`, on the JVM and on node.

  Reads a job file written by `common/scripts/tokenscript-bench/bench.mjs`
  and writes results in the same format as the JavaScript engines
  (`harness.mjs`).

    JVM:  clojure -M:dev -m tokenscript-bench <job.json>
    node: node --expose-gc target/tokenscript-bench/engine.cjs <job.json>
          (shadow-cljs build :tokenscript-bench)

  `parity <inputs.edn> <out.edn>` instead of a job file writes the tokens,
  parse results and errors of every input string (a vector in EDN) in a
  platform neutral form, so the JVM and node outputs can be compared
  byte for byte."
  (:require
   #?(:cljs ["fs" :as fs])
   [app.common.data :as d]
   [app.common.json :as json]
   [app.common.tokenscript :as tsc]
   [app.common.tokenscript.lexer :as lex]
   [app.common.tokenscript.util :as u]
   [clojure.walk :as walk]))

(defn- now []
  #?(:clj (/ (System/nanoTime) 1e6)
     :cljs (js/performance.now)))

(defn- gc! []
  #?(:clj (System/gc)
     :cljs (when (exists? js/gc) (js/gc))))

(defn- read-file [path]
  #?(:clj (slurp path)
     :cljs (.readFileSync fs path "utf8")))

(defn- write-file [path content]
  #?(:clj (spit path content)
     :cljs (.writeFileSync fs path content)))

(defn- runtime []
  #?(:clj (str "JVM " (System/getProperty "java.version"))
     :cljs (str "node " (.-version js/process))))

;; Timing ---------------------------------------------------------------------

(defn- stats [samples]
  (let [s (vec (sort samples))
        n (count s)
        at (fn [q] (nth s (min (dec n) (int (Math/floor (* q n))))))
        mean (/ (reduce + s) n)
        sd (Math/sqrt (/ (reduce + (map #(Math/pow (- % mean) 2) s)) n))]
    {:runs n :min (first s) :median (at 0.5) :mean mean :p95 (at 0.95) :max (peek s) :sd sd}))

(defn- measure
  "Same loop as `measure` in harness.mjs: cold first call, warmup, then
  samples until the budget is spent."
  [f {:keys [budgetMs warmupMs minRuns maxRuns]}]
  (gc!)
  (let [t0 (now)
        first-result (f 0)
        cold (- (now) t0)
        slow? (> cold budgetMs)
        min-runs (if slow? (min minRuns 3) minRuns)
        warmup (if slow? 0 warmupMs)
        i (volatile! 1)
        start (now)]
    (loop []
      (f @i)
      (vswap! i inc)
      (when (and (not slow?) (or (< @i 3) (< (- (now) start) warmup)))
        (recur)))
    (let [start (now)
          samples (loop [samples []]
                    (if (and (< (count samples) maxRuns)
                             (or (< (count samples) min-runs)
                                 (< (- (now) start) budgetMs)))
                      (do (gc!)
                          (let [t0 (now)]
                            (f @i)
                            (vswap! i inc)
                            (recur (conj samples (- (now) t0)))))
                      samples))]
      (assoc (stats samples) :cold cold :first first-result))))

;; Engine ---------------------------------------------------------------------

(defn- inputs
  "Fixture tokens as penpot passes them to the resolver: string names,
  `{:type \"color\" :value ..}` with keyword keys inside composite values."
  [tokens]
  (into {}
        (map (fn [[name {:strs [type value]}]]
               [name {:type type :value (walk/keywordize-keys value)}]))
        tokens))

(defn- build-fn [tokens]
  (fn [_] (tsc/build tokens)))

(defn- summarize [res tokens]
  (let [errors (count (tsc/token-errors res))]
    {:resolved (- (count tokens) errors) :errors errors}))

(defn- parse-fn
  "Parses every string value of the fixture without the processor's parse
  cache: the cost of a first build."
  [tokens]
  (let [values (into [] (comp (map (comp :value val)) (filter string?)) tokens)]
    (fn [_]
      (reduce (fn [n v]
                (try (tsc/parse v {:mode :inline}) (inc n)
                     (catch #?(:clj Throwable :cljs :default) _ n)))
              0
              values))))

(defn- update-fn [tokens {:strs [name type values]}]
  (let [state (:state (tsc/build tokens))]
    (fn [i]
      (tsc/update-token state {:token-path name
                               :token-data {:type type :value (nth values (if (even? i) 1 0))}}))))

(defn- run-fixture [job id]
  (let [fixture (json/decode (read-file (str (get job "fixturesDir") "/" id ".json")) :key-fn identity)
        tokens (inputs (get fixture "tokens"))
        opts {:budgetMs (get job "budgetMs") :warmupMs (get job "warmupMs")
              :minRuns (get job "minRuns") :maxRuns (get job "maxRuns")}
        ops (set (get job "ops"))
        base {:engine (get job "engine") :fixture id
              :profile (get fixture "profile") :size (get fixture "size")}
        attempt (fn [op f]
                  (try (f)
                       (catch #?(:clj Throwable :cljs :default) e
                         (assoc base :op op :error (str (ex-message e))))))]
    (cond-> []
      (contains? ops "build")
      (conj (attempt "build"
                     #(let [m (measure (build-fn tokens) opts)]
                        (merge base {:op "build"} (dissoc m :first) (summarize (:first m) tokens)))))

      (contains? ops "parse")
      (conj (attempt "parse"
                     #(let [m (measure (parse-fn tokens) opts)]
                        (merge base {:op "parse" :parsed (:first m)} (dissoc m :first)))))

      (and (contains? ops "update") (get fixture "update"))
      (conj (attempt "update"
                     #(let [upd (get fixture "update")
                            m (measure (update-fn tokens upd) opts)]
                        (merge base {:op "update" :dependents (get upd "dependents")} (dissoc m :first))))))))

(defn run-job [path]
  (let [job (json/decode (read-file path) :key-fn identity)
        t0 (now)
        _ (tsc/default-config)
        init-ms (- (now) t0)
        results (into [] (mapcat #(run-fixture job %)) (get job "ids"))]
    (write-file (get job "out")
                (json/encode {:engine (get job "engine")
                              :version "app.common.tokenscript"
                              :runtime (runtime)
                              :initMs init-ms
                              :results results}))))

;; Parity ---------------------------------------------------------------------

(defn- neutral
  "Platform neutral data: numbers formatted like JavaScript, maps and sets
  sorted, records as maps."
  [x]
  (walk/postwalk
   (fn [v]
     (cond
       (number? v) (u/num->str v)
       (map? v) (into (sorted-map-by #(compare (str %1) (str %2))) v)
       (set? v) (vec (sort-by str v))
       :else v))
   x))

(defn- outcome
  [f]
  (try {:ok (f)}
       (catch #?(:clj Throwable :cljs :default) e
         {:error (ex-message e) :data (ex-data e)})))

(defn run-parity
  [in out]
  (let [inputs (d/read-string (read-file in))
        lexer-modes [nil {:greedy-strings? true} {:tolerant? true} {:tolerant? true :greedy-strings? true}]
        parse-modes [{:mode :inline} {:mode :script} {:mode :inline :tolerant? true} {:mode :script :tolerant? true}]]
    (write-file out (pr-str (neutral (mapv (fn [s]
                                             [s
                                              (mapv #(outcome (fn [] (lex/tokenize s %))) lexer-modes)
                                              (mapv #(outcome (fn [] (tsc/parse s %))) parse-modes)])
                                           inputs))))))

(defn- dispatch
  [args]
  (if (= "parity" (first args))
    (run-parity (nth args 1) (nth args 2))
    (run-job (first args))))

#?(:clj
   (defn -main [& args]
     (dispatch args)
     (shutdown-agents)
     (System/exit 0)))

#?(:cljs
   (defn main [& args]
     (dispatch args)))
