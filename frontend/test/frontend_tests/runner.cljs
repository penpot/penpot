(ns frontend-tests.runner
  (:require
   [app.common.logging :as l]
   [cljs.test :as t]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [frontend-tests.basic-shapes-test]
   [frontend-tests.code-gen-style-test]
   [frontend-tests.copy-as-svg-test]
   [frontend-tests.data.nitrate-test]
   [frontend-tests.data.repo-test]
   [frontend-tests.data.uploads-test]
   [frontend-tests.data.viewer-test]
   [frontend-tests.data.workspace-colors-test]
   [frontend-tests.data.workspace-interactions-test]
   [frontend-tests.data.workspace-mcp-test]
   [frontend-tests.data.workspace-media-test]
   [frontend-tests.data.workspace-shortcuts-test]
   [frontend-tests.data.workspace-texts-test]
   [frontend-tests.data.workspace-thumbnails-test]
   [frontend-tests.errors-test]
   [frontend-tests.helpers-shapes-test]
   [frontend-tests.logic.comp-remove-swap-slots-test]
   [frontend-tests.logic.components-and-tokens]
   [frontend-tests.logic.copying-and-duplicating-test]
   [frontend-tests.logic.frame-guides-test]
   [frontend-tests.logic.groups-test]
   [frontend-tests.logic.pasting-in-containers-test]
   [frontend-tests.main-errors-test]
   [frontend-tests.plugins.context-shapes-test]
   [frontend-tests.plugins.format-test]
   [frontend-tests.plugins.interactions-test]
   [frontend-tests.plugins.page-active-validation-test]
   [frontend-tests.plugins.page-test]
   [frontend-tests.plugins.parser-test]
   [frontend-tests.plugins.text-test]
   [frontend-tests.plugins.tokens-test]
   [frontend-tests.plugins.utils-test]
   [frontend-tests.render-wasm.process-objects-test]
   [frontend-tests.svg-fills-test]
   [frontend-tests.tokens.import-export-test]
   [frontend-tests.tokens.logic.token-actions-test]
   [frontend-tests.tokens.logic.token-data-test]
   [frontend-tests.tokens.logic.token-remapping-test]
   [frontend-tests.tokens.style-dictionary-test]
   [frontend-tests.tokens.token-errors-test]
   [frontend-tests.tokens.workspace-tokens-remap-test]
   [frontend-tests.ui.ds-controls-numeric-input-test]
   [frontend-tests.util-object-test]
   [frontend-tests.util-range-tree-test]
   [frontend-tests.util-simple-math-test]
   [frontend-tests.util-webapi-test]
   [frontend-tests.worker-snap-test]
   [goog.object :as gobj]))

(enable-console-print!)

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (.exit js/process 0)
    (.exit js/process 1)))

(def test-namespaces
  '[frontend-tests.basic-shapes-test
    frontend-tests.code-gen-style-test
    frontend-tests.copy-as-svg-test
    frontend-tests.data.nitrate-test
    frontend-tests.data.repo-test
    frontend-tests.errors-test
    frontend-tests.main-errors-test
    frontend-tests.data.uploads-test
    frontend-tests.data.viewer-test
    frontend-tests.data.workspace-colors-test
    frontend-tests.data.workspace-interactions-test
    frontend-tests.data.workspace-mcp-test
    frontend-tests.data.workspace-media-test
    frontend-tests.data.workspace-shortcuts-test
    frontend-tests.data.workspace-texts-test
    frontend-tests.data.workspace-thumbnails-test
    frontend-tests.helpers-shapes-test
    frontend-tests.logic.comp-remove-swap-slots-test
    frontend-tests.logic.components-and-tokens
    frontend-tests.logic.copying-and-duplicating-test
    frontend-tests.logic.frame-guides-test
    frontend-tests.logic.groups-test
    frontend-tests.logic.pasting-in-containers-test
    frontend-tests.plugins.context-shapes-test
    frontend-tests.plugins.page-active-validation-test
    frontend-tests.plugins.interactions-test
    frontend-tests.plugins.format-test
    frontend-tests.plugins.page-test
    frontend-tests.plugins.parser-test
    frontend-tests.plugins.text-test
    frontend-tests.plugins.tokens-test
    frontend-tests.plugins.utils-test
    frontend-tests.svg-fills-test
    frontend-tests.tokens.import-export-test
    frontend-tests.tokens.logic.token-actions-test
    frontend-tests.tokens.logic.token-data-test
    frontend-tests.tokens.logic.token-remapping-test
    frontend-tests.tokens.style-dictionary-test
    frontend-tests.tokens.token-errors-test
    frontend-tests.tokens.workspace-tokens-remap-test
    frontend-tests.ui.ds-controls-numeric-input-test
    frontend-tests.render-wasm.process-objects-test
    frontend-tests.util-object-test
    frontend-tests.util-range-tree-test
    frontend-tests.util-simple-math-test
    frontend-tests.util-webapi-test
    frontend-tests.worker-snap-test])

(assert (every? find-ns-obj test-namespaces)
        "test-namespaces contains a namespace that isn't required in runner.cljs")

;; This runner intentionally mirrors common-tests.runner. Both runners need
;; forwarded CLI args, focused namespace/var execution, fixture preservation,
;; and app log-level setup. A shared helper could own those mechanics, but we
;; keep the logic local while there are only two test targets because sharing
;; it would add cross-module test classpath coupling.
(def ^:private log-levels
  #{:trace :debug :info :warn :error})

(def cli-options
  [["-f" "--focus FOCUS" "Run one test namespace or one test var, e.g. frontend-tests.logic.components-and-tokens/change-token-in-main"]
   ["-l" "--log-level LEVEL" "Set app logger level: trace|debug|info|warn|error"
    :parse-fn keyword
    :validate [log-levels "must be one of trace, debug, info, warn, error"]]
   ["-h" "--help"]])

(defn- argv
  []
  (let [args (->> (.-argv js/process)
                  (array-seq)
                  (drop 2))]
    ;; `pnpm run test -- --focus ...` forwards the separator to the node
    ;; process, so drop one leading `--` before handing args to tools.cli.
    (cond-> args
      (= "--" (first args)) rest)))

(defn- usage
  [summary]
  (str "Usage: pnpm run test -- [options]\n\n"
       "Options:\n"
       summary "\n\n"
       "Focus examples:\n"
       "  pnpm run test -- --focus frontend-tests.logic.components-and-tokens\n"
       "  pnpm run test -- --focus frontend-tests.logic.components-and-tokens/change-token-in-main\n\n"
       "Log level example (quiets app logging during the run):\n"
       "  pnpm run test -- --focus frontend-tests.logic.groups-test --log-level warn"))

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
    (t/run-block
     (concat [(fn [] (t/set-env! env))]
             (t/test-vars-block vars)
             [(fn []
                (vswap! summary
                        (partial merge-with +)
                        (:report-counters (t/get-and-clear-env!))))
              (fn []
                (t/set-env! env)
                (t/do-report @summary)
                (t/report (assoc @summary :type :end-run-tests))
                (t/clear-env!))]))))

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
        (when-let [level (:log-level options)]
          (l/setup! {:app level}))
        (if (:focus options)
          (run-focused-test! (:focus options))
          (run-test-vars! (map #(selected-tests {:ns %}) test-namespaces)))))))
