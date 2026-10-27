;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.shape-test
  (:require
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.render-wasm.shape :as shape]
   [cljs.test :as t]))

(t/deftest fresh-proxies-keep-the-records-base-fields
  (let [attrs {:id (uuid/next) :type :rect :name "Rect" :opacity 0.5}
        expected (into {} (cts/map->Shape attrs))
        actual (shape/create-shape attrs)]
    (t/is (= expected (into {} actual)))
    (doseq [attr [:rotation :flip-x :flip-y :transform :transform-inverse]]
      (t/testing (name attr)
        (t/is (contains? actual attr))
        (t/is (= expected (into {} (dissoc (assoc actual attr true) attr))))))))

(t/deftest reflected-nested-proxies-undo-without-adding-flip-fields
  (with-redefs [cts/wasm-enabled? true
                cts/wasm-create-shape shape/create-shape]
    (let [result (cg/run {:world [2 1 {:text true :layout :grid}]
                          :ops [[:nest 11] [:rotate 1] [:transform 2 16 0 18]]})]
      (t/is (= 3 (count (:trace result)))))))
