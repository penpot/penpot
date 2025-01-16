;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.components-test
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-valid-touched-group
  (t/is (ctk/valid-touched-group? :name-group))
  (t/is (ctk/valid-touched-group? :geometry-group))
  (t/is (ctk/valid-touched-group? :swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f))
  (t/is (not (ctk/valid-touched-group? :this-is-not-a-group)))
  (t/is (not (ctk/valid-touched-group? :swap-slot-)))
  (t/is (not (ctk/valid-touched-group? :swap-slot-xxxxxx)))
  (t/is (not (ctk/valid-touched-group? :swap-slot-9cc181fa-5eef-8084-8004)))
  (t/is (not (ctk/valid-touched-group? nil))))

(t/deftest test-get-swap-slot
  (let [s1 (ths/sample-shape :s1)
        s2 (ths/sample-shape :s2 :touched #{:visibility-group})
        s3 (ths/sample-shape :s3 :touched #{:swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f})
        s4 (ths/sample-shape :s4 :touched #{:fill-group
                                            :swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f})
        s5 (ths/sample-shape :s5 :touched #{:swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f
                                            :content-group
                                            :geometry-group})
        s6 (ths/sample-shape :s6 :touched #{:swap-slot-9cc181fa})]
    (t/is (nil? (ctk/get-swap-slot s1)))
    (t/is (nil? (ctk/get-swap-slot s2)))
    (t/is (= (ctk/get-swap-slot s3) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (= (ctk/get-swap-slot s4) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (= (ctk/get-swap-slot s5) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (nil? (ctk/get-swap-slot s6)))))
