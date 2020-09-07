;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-util-svg
  (:require
   [clojure.test :as t]
   [clojure.java.io :as io]
   [app.http :as http]
   [app.util.svg :as svg]
   [app.tests.helpers :as th]))

(t/deftest parse-svg-1
  (let [result (-> (io/resource "app/tests/_files/sample1.svg")
                   (svg/parse))]
    (t/is (contains? result :width))
    (t/is (contains? result :height))
    (t/is (contains? result :view-box))
    (t/is (contains? result :name))
    (t/is (contains? result :content))
    (t/is (= 500.0 (:width result)))
    (t/is (= 500.0 (:height result)))
    (t/is (= [0.0 0.0 500.00001 500.00001] (:view-box result)))
    (t/is (= "lock.svg" (:name result)))))

(t/deftest parse-svg-2
  (let [result (-> (io/resource "app/tests/_files/sample2.svg")
                   (svg/parse))]
      (t/is (contains? result :width))
      (t/is (contains? result :height))
      (t/is (contains? result :view-box))
      (t/is (contains? result :name))
      (t/is (contains? result :content))
      (t/is (= 500.0 (:width result)))
      (t/is (= 500.0 (:height result)))
      (t/is (= [0.0 0.0 500.0 500.00001] (:view-box result)))
      (t/is (= "play.svg" (:name result)))))

(t/deftest parse-invalid-svg-1
  (let [image (io/resource "app/tests/_files/sample.jpg")
        out (th/try! (svg/parse image))]

    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-code? error ::svg/invalid-input)))))

(t/deftest parse-invalid-svg-2
  (let [out (th/try! (svg/parse-string ""))]
    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-code? error ::svg/invalid-input)))))

(t/deftest parse-invalid-svg-3
  (let [out (th/try! (svg/parse-string "<svg></svg>"))]
    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-code? error ::svg/invalid-result)))))
