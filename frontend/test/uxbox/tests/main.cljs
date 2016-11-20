(ns uxbox.tests.main
  (:require [cljs.test :as test]
            [uxbox.tests.geom-types]
            [uxbox.tests.shapes-state]))

(enable-console-print!)

(defn main
  []
  (test/run-tests
   (test/empty-env)
   'uxbox.tests.geom-types
   'uxbox.tests.shapes-state
   ))

(defmethod test/report [:cljs.test/default :end-run-tests]
  [m]
  (if (test/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))

(set! *main-cli-fn* main)
