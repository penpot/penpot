;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.runner
  (:require
   #?(:clj [common-tests.fressian-test])
   #?(:cljs [app.common.logging :as l])
   #?(:cljs [clojure.string :as str])
   #?(:cljs [clojure.tools.cli :refer [parse-opts]])
   #?(:cljs [goog.object :as gobj])
   [clojure.test :as t]
   [common-tests.attrs-test]
   [common-tests.buffer-test]
   [common-tests.colors-test]
   [common-tests.data-test]
   [common-tests.files-builder-test]
   [common-tests.files-changes-test]
   [common-tests.files-migrations-test]
   [common-tests.files.shapes-builder-test]
   [common-tests.files.validate-test]
   [common-tests.geom-align-test]
   [common-tests.geom-bounds-map-test]
   [common-tests.geom-flex-layout-test]
   [common-tests.geom-grid-layout-test]
   [common-tests.geom-grid-test]
   [common-tests.geom-line-test]
   [common-tests.geom-modif-tree-test]
   [common-tests.geom-modifiers-test]
   [common-tests.geom-point-test]
   [common-tests.geom-proportions-test]
   [common-tests.geom-rect-test]
   [common-tests.geom-shapes-common-test]
   [common-tests.geom-shapes-constraints-test]
   [common-tests.geom-shapes-corners-test]
   [common-tests.geom-shapes-effects-test]
   [common-tests.geom-shapes-intersect-test]
   [common-tests.geom-shapes-strokes-test]
   [common-tests.geom-shapes-test]
   [common-tests.geom-shapes-text-test]
   [common-tests.geom-shapes-tree-seq-test]
   [common-tests.geom-snap-test]
   [common-tests.geom-test]
   [common-tests.logic.chained-propagation-test]
   [common-tests.logic.comp-creation-test]
   [common-tests.logic.comp-detach-with-nested-test]
   [common-tests.logic.comp-remove-swap-slots-test]
   [common-tests.logic.comp-reset-test]
   [common-tests.logic.comp-sync-test]
   [common-tests.logic.comp-touched-test]
   [common-tests.logic.copying-and-duplicating-test]
   [common-tests.logic.duplicated-pages-test]
   [common-tests.logic.move-shapes-test]
   [common-tests.logic.multiple-nesting-levels-test]
   [common-tests.logic.swap-and-reset-test]
   [common-tests.logic.swap-as-override-test]
   [common-tests.logic.token-test]
   [common-tests.logic.variants-switch-test]
   [common-tests.media-test]
   [common-tests.path-names-test]
   [common-tests.record-test]
   [common-tests.schema-test]
   [common-tests.spec-test]
   [common-tests.svg-path-test]
   [common-tests.svg-test]
   [common-tests.text-test]
   [common-tests.time-test]
   [common-tests.types.absorb-assets-test]
   [common-tests.types.color-test]
   [common-tests.types.components-test]
   [common-tests.types.container-test]
   [common-tests.types.fill-test]
   [common-tests.types.modifiers-test]
   [common-tests.types.nitrate-permissions-test]
   [common-tests.types.objects-map-test]
   [common-tests.types.path-data-test]
   [common-tests.types.shape-decode-encode-test]
   [common-tests.types.shape-interactions-test]
   [common-tests.types.shape-layout-test]
   [common-tests.types.token-test]
   [common-tests.types.tokens-lib-test]
   [common-tests.undo-stack-test]
   [common-tests.uuid-test]))

(def test-namespaces
  [#?(:clj 'common-tests.fressian-test)
   'common-tests.attrs-test
   'common-tests.buffer-test
   'common-tests.colors-test
   'common-tests.data-test
   'common-tests.files-changes-test
   'common-tests.files-builder-test
   'common-tests.files-migrations-test
   'common-tests.files.validate-test
   'common-tests.geom-align-test
   'common-tests.geom-bounds-map-test
   'common-tests.geom-flex-layout-test
   'common-tests.geom-grid-layout-test
   'common-tests.geom-grid-test
   'common-tests.geom-line-test
   'common-tests.geom-modif-tree-test
   'common-tests.geom-modifiers-test
   'common-tests.geom-point-test
   'common-tests.geom-proportions-test
   'common-tests.geom-rect-test
   'common-tests.geom-shapes-common-test
   'common-tests.geom-shapes-constraints-test
   'common-tests.geom-shapes-corners-test
   'common-tests.geom-shapes-effects-test
   'common-tests.geom-shapes-intersect-test
   'common-tests.geom-shapes-strokes-test
   'common-tests.geom-shapes-test
   'common-tests.geom-shapes-text-test
   'common-tests.geom-shapes-tree-seq-test
   'common-tests.geom-snap-test
   'common-tests.geom-test
   'common-tests.logic.chained-propagation-test
   'common-tests.logic.comp-creation-test
   'common-tests.logic.comp-detach-with-nested-test
   'common-tests.logic.comp-remove-swap-slots-test
   'common-tests.logic.comp-reset-test
   'common-tests.logic.comp-sync-test
   'common-tests.logic.comp-touched-test
   'common-tests.logic.copying-and-duplicating-test
   'common-tests.logic.duplicated-pages-test
   'common-tests.logic.move-shapes-test
   'common-tests.logic.multiple-nesting-levels-test
   'common-tests.logic.swap-and-reset-test
   'common-tests.logic.swap-as-override-test
   'common-tests.logic.token-test
   'common-tests.logic.variants-switch-test
   'common-tests.media-test
   'common-tests.path-names-test
   'common-tests.record-test
   'common-tests.schema-test
   'common-tests.spec-test
   'common-tests.svg-path-test
   'common-tests.svg-test
   'common-tests.text-test
   'common-tests.time-test
   'common-tests.types.absorb-assets-test
   'common-tests.types.color-test
   'common-tests.types.components-test
   'common-tests.types.container-test
   'common-tests.types.fill-test
   'common-tests.types.modifiers-test
   'common-tests.types.nitrate-permissions-test
   'common-tests.types.objects-map-test
   'common-tests.types.path-data-test
   'common-tests.types.shape-decode-encode-test
   'common-tests.types.shape-interactions-test
   'common-tests.types.shape-layout-test
   'common-tests.types.token-test
   'common-tests.types.tokens-lib-test
   'common-tests.undo-stack-test
   'common-tests.uuid-test])

#?(:cljs
   (assert (every? find-ns-obj test-namespaces)
           "test-namespaces contains a namespace that isn't required in runner.cljc"))

#?(:cljs (enable-console-print!))

#?(:cljs
   (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
     (if (cljs.test/successful? m)
       (.exit js/process 0)
       (.exit js/process 1))))
#?(:cljs
   (defmethod t/report [:cljs.test/default :begin-test-var] [m]
     (let [v (:var m)]
       (println (str "  ▸ " (:ns (meta v)) "/" (:name (meta v)))))))

#?(:cljs
   (do
     ;; This runner intentionally mirrors frontend-tests.runner. Both runners need
     ;; forwarded CLI args, focused namespace/var execution, fixture preservation,
     ;; and app log-level setup. A shared helper could own those mechanics, but we
     ;; keep the logic local while there are only two test targets because sharing
     ;; it would add cross-module test classpath coupling.
     (def ^:private log-levels
       #{:trace :debug :info :warn :error})

     (def cli-options
       [["-f" "--focus FOCUS" "Run one test namespace or one test var, e.g. common-tests.logic.comp-sync-test/test-sync-when-changing-attribute"]
        ["-l" "--log-level LEVEL" "Set app logger level: trace|debug|info|warn|error"
         :parse-fn keyword
         :validate [log-levels "must be one of trace, debug, info, warn, error"]]
        ["-h" "--help"]])

     (defn- argv
       []
       (let [args (->> (.-argv js/process)
                       (array-seq)
                       (drop 2))]
         (cond-> args
           (= "--" (first args)) rest)))

     (defn- usage
       [summary]
       (str "Usage: pnpm run test:js -- [options]\n\n"
            "Options:\n"
            summary "\n\n"
            "Focus examples:\n"
            "  pnpm run test:js -- --focus common-tests.logic.comp-sync-test\n"
            "  pnpm run test:js -- --focus common-tests.logic.comp-sync-test/test-sync-when-changing-attribute\n\n"
            "Log level example (quiets app logging during the run):\n"
            "  pnpm run test:js -- --focus common-tests.logic.comp-sync-test --log-level warn"))

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
                             (:report-counters (t/get-and-clear-env!))))
              (fn []
                (t/set-env! env)
                (t/report @summary)
                (t/report (assoc @summary :type :end-run-tests))
                (t/clear-env!))]))))

     (defn- run-focused-test!
       [focus]
       (run-test-vars! [(selected-tests (parse-focus focus))]))

     (defn- run-all-tests!
       []
       (run-test-vars! (map #(selected-tests {:ns %}) test-namespaces)))))

(defn -main
  [& _args]
  #?(:cljs
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
             (run-all-tests!)))))
     :clj
     (apply t/run-tests test-namespaces)))
