;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.lookup-test
  (:require
   [app.common.types.objects-map :as omap]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tokens.lookup :as lookup]
   [cljs.test :as t :include-macros true]))

(def ^:private page-id (uuid/next))
(def ^:private id-a (uuid/next))
(def ^:private id-b (uuid/next))
(def ^:private id-c (uuid/next))

(defn- sample-objects
  []
  {id-a {:id id-a :applied-tokens {:fill "red" :r1 "radius"}}
   id-b {:id id-b :applied-tokens {:fill "red"}}
   id-c {:id id-c}})

(t/deftest index-of-a-page
  (let [index (lookup/token-shape-ids (sample-objects))]
    (t/is (= #{id-a id-b} (get index "red")))
    (t/is (= #{id-a} (get index "radius")))
    (t/is (= #{id-a id-b} (lookup/shapes-using (sample-objects) ["red" "radius"])))))

(t/deftest index-of-encoded-shapes
  (let [objects (omap/create (omap/wrap (sample-objects)))]
    (t/is (= #{id-a id-b} (get (lookup/token-shape-ids objects) "red")))
    (t/is (string? (omap/get-encoded objects id-a)) "the shapes are not decoded")))

(t/deftest advance-follows-the-changes
  (let [objects  (sample-objects)
        _        (lookup/token-shape-ids objects)
        objects' (-> objects
                     (assoc-in [id-b :applied-tokens] {:fill "blue"})
                     (assoc-in [id-c :applied-tokens] {:r1 "radius"})
                     (dissoc id-a))
        changes  [{:type :mod-obj :page-id page-id :id id-b
                   :operations [{:type :set :attr :applied-tokens :val {:fill "blue"}}]}
                  {:type :mod-obj :page-id page-id :id id-c
                   :operations [{:type :set :attr :applied-tokens :val {:r1 "radius"}}]}
                  {:type :del-obj :page-id page-id :id id-a}]]
    (lookup/advance page-id objects objects' changes)
    (let [index (lookup/token-shape-ids objects')]
      (t/is (empty? (get index "red")))
      (t/is (= #{id-b} (get index "blue")))
      (t/is (= #{id-c} (get index "radius"))))))

(t/deftest advance-keeps-the-index-for-other-changes
  (let [objects  (sample-objects)
        index    (lookup/token-shape-ids objects)
        objects' (assoc-in objects [id-a :r1] 4)]
    (lookup/advance page-id objects objects'
                    [{:type :mod-obj :page-id page-id :id id-a
                      :operations [{:type :set :attr :r1 :val 4}]}])
    (t/is (identical? index (lookup/token-shape-ids objects')))))

(t/deftest parent-of-an-encoded-shape
  (let [parent-id (uuid/next)
        objects   (omap/create (omap/wrap {id-a {:id id-a :parent-id parent-id :name "a"}
                                           id-b {:id id-b :name "b"}}))]
    (t/is (= parent-id (lookup/parent-id objects id-a)))
    (t/is (string? (omap/get-encoded objects id-a)) "the shape is not decoded")
    (t/is (nil? (lookup/parent-id objects id-b)))
    (t/is (= parent-id (lookup/parent-id {id-a {:id id-a :parent-id parent-id}} id-a))
          "a map of decoded shapes")))
