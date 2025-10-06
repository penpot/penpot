(ns frontend-tests.runner
  (:require
   [cljs.test :as t]
   [frontend-tests.basic-shapes-test]
   [frontend-tests.data.workspace-colors-test]
   [frontend-tests.helpers-shapes-test]
   [frontend-tests.logic.comp-remove-swap-slots-test]
   [frontend-tests.logic.components-and-tokens]
   [frontend-tests.logic.copying-and-duplicating-test]
   [frontend-tests.logic.frame-guides-test]
   [frontend-tests.logic.groups-test]
   [frontend-tests.plugins.context-shapes-test]
   [frontend-tests.tokens.import-export-test]
   [frontend-tests.tokens.logic.token-actions-test]
   [frontend-tests.tokens.logic.token-data-test]
   [frontend-tests.tokens.style-dictionary-test]
   [frontend-tests.tokens.token-form-test]
   [frontend-tests.util-range-tree-test]
   [frontend-tests.util-simple-math-test]
   [frontend-tests.worker-snap-test]))

(enable-console-print!)

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (.exit js/process 0)
    (.exit js/process 1)))

(defn init
  []
  (t/run-tests
   'frontend-tests.basic-shapes-test
   'frontend-tests.data.workspace-colors-test
   'frontend-tests.helpers-shapes-test
   'frontend-tests.logic.comp-remove-swap-slots-test
   'frontend-tests.logic.components-and-tokens
   'frontend-tests.logic.copying-and-duplicating-test
   'frontend-tests.logic.frame-guides-test
   'frontend-tests.logic.groups-test
   'frontend-tests.plugins.context-shapes-test
   'frontend-tests.tokens.import-export-test
   'frontend-tests.tokens.logic.token-actions-test
   'frontend-tests.tokens.logic.token-data-test
   'frontend-tests.tokens.style-dictionary-test
   'frontend-tests.tokens.token-form-test
   'frontend-tests.util-range-tree-test
   'frontend-tests.util-simple-math-test
   'frontend-tests.worker-snap-test))
