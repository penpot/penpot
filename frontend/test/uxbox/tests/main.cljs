(ns uxbox.tests.main
  (:require [cljs.test :as test]
            [uxbox.tests.test-util-geom]
            [uxbox.tests.test-main-data-shapes-impl]))

(enable-console-print!)

(defn main
  []
  (test/run-tests
   (test/empty-env)
   'uxbox.tests.test-util-geom
   'uxbox.tests.test-main-data-shapes-impl
   ))

(defmethod test/report [:cljs.test/default :end-run-tests]
  [m]
  (if (test/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))

(set! *main-cli-fn* main)
