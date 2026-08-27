;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-export-images-test
  (:require
   [app.main.render :as render]
   [cljs.test :refer [deftest is testing]]))

(def image-a
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :name "image-a"})

(def image-b
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :name "image-b"})

(deftest export-image-cache-includes-images-from-fills-vector
  (let [shape {:type :rect
               :fills [{:fill-color "#ffffff"
                        :fill-opacity 1}
                       {:fill-opacity 1
                        :fill-image image-a}
                       {:fill-opacity 0.5
                        :fill-image image-b}]}
        images (#'render/get-image-data shape)]
    (testing "all image fills are preloaded before static export markup is rendered"
      (is (= [image-a image-b] images)))))
