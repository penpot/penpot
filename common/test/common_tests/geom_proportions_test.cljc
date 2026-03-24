;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-proportions-test
  (:require
   [app.common.geom.proportions :as gpr]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest assign-proportions-test
  (t/testing "Assigns proportion from selrect"
    (let [shape {:selrect {:x 0 :y 0 :width 200 :height 100}}
          result (gpr/assign-proportions shape)]
      (t/is (mth/close? 2.0 (:proportion result)))))

  (t/testing "Square shape has proportion 1"
    (let [shape {:selrect {:x 0 :y 0 :width 50 :height 50}}
          result (gpr/assign-proportions shape)]
      (t/is (mth/close? 1.0 (:proportion result))))))

(t/deftest setup-proportions-image-test
  (t/testing "Sets proportion and lock from metadata"
    (let [shape {:metadata {:width 300 :height 150}}
          result (gpr/setup-proportions-image shape)]
      (t/is (mth/close? 2.0 (:proportion result)))
      (t/is (true? (:proportion-lock result))))))

(t/deftest setup-proportions-size-test
  (t/testing "Sets proportion from selrect"
    (let [shape {:selrect {:x 0 :y 0 :width 400 :height 200}}
          result (gpr/setup-proportions-size shape)]
      (t/is (mth/close? 2.0 (:proportion result)))
      (t/is (true? (:proportion-lock result))))))

(t/deftest setup-proportions-const-test
  (t/testing "Sets proportion to 1.0 and lock to false"
    (let [shape {:selrect {:x 0 :y 0 :width 200 :height 100}}
          result (gpr/setup-proportions-const shape)]
      (t/is (mth/close? 1.0 (:proportion result)))
      (t/is (false? (:proportion-lock result))))))

(t/deftest setup-proportions-test
  (t/testing "Image type uses image proportions"
    (let [shape {:type :image :metadata {:width 300 :height 150} :fills []}
          result (gpr/setup-proportions shape)]
      (t/is (mth/close? 2.0 (:proportion result)))
      (t/is (true? (:proportion-lock result)))))

  (t/testing "svg-raw type uses size proportions"
    (let [shape {:type :svg-raw :selrect {:x 0 :y 0 :width 200 :height 100} :fills []}
          result (gpr/setup-proportions shape)]
      (t/is (mth/close? 2.0 (:proportion result)))
      (t/is (true? (:proportion-lock result)))))

  (t/testing "Text type keeps existing props"
    (let [shape {:type :text :selrect {:x 0 :y 0 :width 200 :height 100}}
          result (gpr/setup-proportions shape)]
      (t/is (= shape result))))

  (t/testing "Rect type with fill-image uses size proportions"
    (let [shape {:type :rect
                 :selrect {:x 0 :y 0 :width 200 :height 100}
                 :fills [{:fill-image {:width 300 :height 150}}]}
          result (gpr/setup-proportions shape)]
      (t/is (mth/close? 2.0 (:proportion result)))
      (t/is (true? (:proportion-lock result)))))

  (t/testing "Rect type without fill-image uses const proportions"
    (let [shape {:type :rect
                 :selrect {:x 0 :y 0 :width 200 :height 100}
                 :fills []}
          result (gpr/setup-proportions shape)]
      (t/is (mth/close? 1.0 (:proportion result)))
      (t/is (false? (:proportion-lock result))))))
