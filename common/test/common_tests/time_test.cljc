;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.time-test
  (:require
   [app.common.time :as dt]
   [clojure.test :as t]))

(t/deftest compare-time
  (let [dta (dt/inst 10000)
        dtb (dt/inst 20000)]
    (t/is (false? (dt/is-after? dta dtb)))
    (t/is (true? (dt/is-before? dta dtb)))))
