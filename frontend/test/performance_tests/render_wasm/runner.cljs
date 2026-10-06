;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.runner
  (:require
   [app.common.logging :as l]
   [cljs.test :as t]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [goog.object :as gobj]
   [performance-tests.render-wasm.boolean-test]
   [performance-tests.render-wasm.builder-test]
   [performance-tests.render-wasm.containers-test]
   [performance-tests.render-wasm.contracts-test]
   [performance-tests.render-wasm.fingerprint-test]
   [performance-tests.render-wasm.geometry-scenes-test]
   [performance-tests.render-wasm.lifecycle-test]
   [performance-tests.render-wasm.protocol-test]
   [performance-tests.render-wasm.report-test]
   [performance-tests.render-wasm.scene-snapshot-test]))

(enable-console-print!)

(defmethod t/report [:cljs.test/default :begin-test-var] [m]
  (let [v (:var m)]
    (println (str "  ▸ " (:ns (meta v)) "/" (:name (meta v))))))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (.exit js/process 0)
    (.exit js/process 1)))

(def test-namespaces
  ['performance-tests.render-wasm.boolean-test
   'performance-tests.render-wasm.builder-test
   'performance-tests.render-wasm.containers-test
   'performance-tests.render-wasm.contracts-test
   'performance-tests.render-wasm.fingerprint-test
   'performance-tests.render-wasm.geometry-scenes-test
   'performance-tests.render-wasm.lifecycle-test
   'performance-tests.render-wasm.protocol-test
   'performance-tests.render-wasm.report-test
   'performance-tests.render-wasm.scene-snapshot-test])

(assert (every? find-ns-obj test-namespaces)
        "test-namespaces contains a namespace that isn't required in runner.cljs")

;; This runner mirrors frontend-tests.runner for the bench-only
;; :performance-tests target. Both runners need forwarded CLI args, focused
;; namespace/var execution, fixture preservation, and app log-level
;; setup. The logic stays local to avoid cross-target test classpath
;; coupling.
(def ^:private log-levels
  #{:trace :debug :info :warn :error})

(def cli-options
  [["-f" "--focus FOCUS" "Run one test namespace or one test var, e.g. performance-tests.render-wasm.contracts-test"]
   ["-l" "--log-level LEVEL" "Set app logger level: trace|debug|info|warn|error"
    :parse-fn keyword
    :validate [log-levels "must be one of trace, debug, info, warn, error"]]
   ["-h" "--help"]])

(defn- argv
  []
  (let [args (->> (.-argv js/process)
                  (array-seq)
                  (drop 2))]
    ;; `pnpm run test:performance-tests -- --focus ...` forwards the separator to
    ;; the node process, so drop one leading `--` before handing args to
    ;; tools.cli.
    (cond-> args
      (= "--" (first args)) rest)))

(defn- usage
  [summary]
  (str "Usage: pnpm run test:performance-tests -- [options]\n\n"
       "Options:\n"
       summary "\n\n"
       "Focus examples:\n"
       "  pnpm run test:performance-tests -- --focus performance-tests.render-wasm.contracts-test\n"
       "  pnpm run test:performance-tests -- --focus performance-tests.render-wasm.contracts-test/some-test-var\n\n"
       "Log level example (quiets app logging during the run):\n"
       "  pnpm run test:performance-tests -- --focus performance-tests.render-wasm.contracts-test --log-level warn"))

(defn- fail!
  [message]
  (js/console.error message)
  (.exit js/process 1))

(defn- parse-focus
  [focus]
  (let [[ns-name test-name & extra] (str/split focus #"/")]
    (cond
      (or (str/blank? ns-name) (seq extra))
      (fail! (str "Invalid --focus value: " focus))

      (some? test-name)
      {:ns (symbol ns-name) :test test-name}

      :else
      {:ns (symbol ns-name)})))

(defn- fixture-value
  [ns-obj fixture-name]
  (let [value (gobj/get ns-obj (munge fixture-name))]
    (when-not (undefined? value)
      value)))

(defn- ns-test-vars
  [ns-sym]
  (when-let [ns-obj (find-ns-obj ns-sym)]
    (->> (js-keys ns-obj)
         (keep (fn [key]
                 (some-> (gobj/get ns-obj key)
                         (.-cljs$lang$var))))
         (filter (comp :test meta))
         (sort-by (comp :line meta)))))

(defn- ns-fixtures
  [ns-sym vars]
  (when-let [ns-obj (find-ns-obj ns-sym)]
    (let [ns-key        (or (some-> vars first meta :ns) ns-sym)
          once-fixtures (fixture-value ns-obj "cljs-test-once-fixtures")
          each-fixtures (fixture-value ns-obj "cljs-test-each-fixtures")]
      {:once (when once-fixtures {ns-key once-fixtures})
       :each (when each-fixtures {ns-key each-fixtures})})))

(defn- selected-tests
  [{:keys [ns test]}]
  (when-not (some #{ns} test-namespaces)
    (fail! (str "Unknown test namespace: " ns)))
  (let [vars (vec (ns-test-vars ns))]
    (when (empty? vars)
      (fail! (str "No tests found in namespace: " ns)))
    (if test
      (let [test-sym (symbol test)
            test-var (some #(when (= test-sym (:name (meta %))) %) vars)]
        (if test-var
          {:vars [test-var]
           :fixtures (ns-fixtures ns [test-var])}
          (fail! (str "Unknown test var: " ns "/" test))))
      {:vars vars
       :fixtures (ns-fixtures ns vars)})))

(defn- merge-fixtures
  [fixtures]
  {:once (apply merge (keep :once fixtures))
   :each (apply merge (keep :each fixtures))})

(defn- run-test-vars!
  [tests]
  (let [vars     (vec (mapcat :vars tests))
        fixtures (merge-fixtures (map :fixtures tests))
        env      (assoc (t/empty-env)
                        :once-fixtures (:once fixtures)
                        :each-fixtures (:each fixtures))
        summary  (volatile! {:test 0 :pass 0 :fail 0 :error 0 :type :summary})]

    (t/set-env! env)

    (t/run-block
     (concat (t/test-vars-block vars)
             [(fn []
                (vswap! summary
                        (partial merge-with +)
                        (:report-counters (t/get-current-env))))
              (fn []
                (t/report @summary)
                (t/report (assoc @summary :type :end-run-tests)))]))))

(defn- run-focused-test!
  [focus]
  (run-test-vars! [(selected-tests (parse-focus focus))]))

(defn init
  []
  (let [{:keys [options errors summary]} (parse-opts (argv) cli-options)]
    (cond
      (seq errors)
      (fail! (str/join "\n" errors))

      (:help options)
      (do
        (println (usage summary))
        (.exit js/process 0))

      :else
      (do
        (l/setup! {:app (or (:log-level options) :warn)})

        (if (:focus options)
          (run-focused-test! (:focus options))
          (run-test-vars! (map #(selected-tests {:ns %}) test-namespaces)))))))
