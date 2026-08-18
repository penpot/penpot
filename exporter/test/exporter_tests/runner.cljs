;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.runner
  (:require
   [clojure.test :as t]
   [exporter-tests.renderer-svg-test]))

(enable-console-print!)

(defmethod t/report [:cljs.test/default :end-run-tests]
  [result]
  (.exit js/process (if (cljs.test/successful? result) 0 1)))

(defn -main
  []
  (t/run-tests 'exporter-tests.renderer-svg-test))
