;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.time-test
  (:require
   [app.common.time :as dt]
   [clojure.test :as t]))

(t/deftest compare-time
  (let [dta (dt/inst 10000)
        dtb (dt/inst 20000)]
    (t/is (false? (dt/is-after? dta dtb)))
    (t/is (true? (dt/is-before? dta dtb)))))

#?(:clj
   (t/deftest parse-duration-test
     (t/is (dt/duration? (dt/parse-duration "10m")))
     (t/is (= (dt/duration "10m") (dt/parse-duration "10m")))
     (t/is (= (dt/duration "1h") (dt/parse-duration "1h")))

     ;; Invalid values are returned unchanged instead of throwing, so
     ;; they fail the `duration?` schema predicate with a clean
     ;; validation error downstream.
     (t/is (= "yes" (dt/parse-duration "yes")))
     (t/is (not (dt/duration? (dt/parse-duration "yes"))))
     (t/is (= true (dt/parse-duration true)))
     (t/is (not (dt/duration? (dt/parse-duration true))))))
