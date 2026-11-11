;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.shape-proxy-test
  (:require
   [app.common.uuid :as uuid]
   [app.render-wasm.shape :as wasm.shape]
   [cljs.test :as t :include-macros true]))

(def ^:private attrs
  {:id (uuid/next) :type :rect :name "Rect" :x 10 :y 20})

(t/deftest count-includes-id-and-type
  (let [shape (wasm.shape/create-shape attrs)]
    (t/is (= (count attrs) (count shape)))
    (t/is (= (count shape) (count (seq shape))))))

(t/deftest equals-plain-map-with-same-entries
  (let [shape (wasm.shape/create-shape attrs)]
    (t/is (= shape attrs))
    (t/is (= attrs shape))))
