(ns uxbox.test-runner
  (:require [cljs.test :as test]
            [uxbox.data.workspace-tests]
            [uxbox.util.geom-tests]))

(enable-console-print!)

(defn main
  []
  (test/run-tests
   (test/empty-env)
   'uxbox.data.workspace-tests
   'uxbox.util.geom-tests
   ))

(defmethod test/report [:cljs.test/default :end-run-tests]
  [m]
  (if (test/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))

(set! *main-cli-fn* main)
