;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.setup-test
  (:require
   [app.common.schema :as sm]
   [app.common.pprint :as pp]
   [cljs.test :as t]))

(.on js/process "uncaughtException" (fn [cause]
                                      (try
                                        (js/console.log "EE" (.-stack cause))
                                        (when-let [data (some-> cause ex-data ::sm/explain)]
                                          (pp/pprint (sm/humanize-explain data)))
                                        (finally
                                          (js/console.log "EXIT")
                                          (.exit js/process -1)))))

(defmethod t/report [:cljs.test/default :end-run-tests]
  [m]
  (if (t/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))

#_(set! *main-cli-fn*
      #(t/run-tests 'frontend-tests.test-snap-data
                    'frontend-tests.test-simple-math
                    'frontend-tests.test-range-tree))
