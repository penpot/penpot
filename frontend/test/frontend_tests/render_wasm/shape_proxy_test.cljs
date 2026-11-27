;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.shape-proxy-test
  (:require
   [app.common.types.shape :as cts]
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

(t/deftest set-attrs-is-assoc-and-dissoc-in-order
  (let [shape   (wasm.shape/create-shape (assoc attrs :hidden true))
        changes [[:x 30] [:width 5] [:hidden nil] [:name "Moved"] [:x 40] [:id (uuid/next)]]
        plain   (reduce (fn [m [k v]] (if (nil? v) (dissoc m k) (assoc m k v))) shape changes)]
    (t/is (= plain (cts/set-attrs shape changes)))
    (t/is (= plain (reduce (fn [m [k v]] (if (nil? v) (dissoc m k) (assoc m k v)))
                           (into {} shape) changes))
          "the same as on a plain map")))

(t/deftest set-attrs-keeps-the-shape-when-nothing-changes
  (let [shape (wasm.shape/create-shape attrs)]
    (t/is (identical? shape (cts/set-attrs shape [[:x 10] [:name "Rect"]])))))

(t/deftest set-attrs-tracks-the-changed-attributes
  (let [shape   (wasm.shape/create-shape attrs)
        changes (js/Map.)]
    (binding [cts/*shape-changes* changes]
      (cts/set-attrs shape [[:x 30] [:width 5]]))
    (t/is (= [:x :width] (vec (.get changes (:id attrs)))))))
