;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.fingerprint-test
  "Tests content identity with production records, paths and collection types.
  Validated scene snapshots retain child order even when maps reorder."
  (:require
   [app.common.geom.matrix :as matrix]
   [app.common.geom.point :as point]
   [app.common.transit :as transit]
   [app.common.types.objects-map :as objects-map]
   [app.common.types.path.impl :as path]
   [app.common.types.shape :as shape]
   [app.common.uuid :as uuid]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.fingerprint :as fingerprint]
   [benches.render-wasm.snapshot :as snapshot]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]
   [linked.map :as linked-map]
   [linked.set :as linked-set]))

(def a #uuid "00000000-0000-0000-0000-000000000001")
(def b #uuid "00000000-0000-0000-0000-000000000002")

(defn- starting-snapshot
  "Builds and validates two canonical rectangles under the root frame."
  []
  (snapshot/validate!
   {:objects {uuid/zero (shape/setup-shape {:id uuid/zero :type :frame :name "Root"
                                            :x 0 :y 0 :width 100 :height 100
                                            :parent-id uuid/zero :frame-id uuid/zero :shapes [a b]})
              a (shape/setup-shape {:id a :type :rect :name "A"
                                    :x 0 :y 0 :width 10 :height 10 :parent-id uuid/zero :frame-id uuid/zero})
              b (shape/setup-shape {:id b :type :rect :name "B"
                                    :x 20 :y 20 :width 10 :height 10 :parent-id uuid/zero :frame-id uuid/zero})}
    :refs {:a a :b b}}))

(t/deftest unordered-representations-have-one-fingerprint
  (let [scene (starting-snapshot)
        first (assoc scene :extra {:map (array-map :a 1 :b 2)
                                   :compound (array-map [:a 1] {:x 1 :y 2} [:b 2] #{:one :two})
                                   :set (set (range 20))})
        second (-> scene
                   (update :objects #(into {} (reverse (seq %))))
                   (assoc :refs (array-map :b b :a a)
                          :extra {:set (set (reverse (range 20)))
                                  :compound (array-map [:b 2] #{:two :one} [:a 1] (array-map :y 2 :x 1))
                                  :map (array-map :b 2 :a 1)}))]
    (t/is (= (fingerprint/snapshot-text first) (fingerprint/snapshot-text second)))
    (t/is (= (fingerprint/fingerprint first) (fingerprint/fingerprint second)))))

(t/deftest ordered-collections-and-child-order-change-the-fingerprint
  (let [scene (starting-snapshot)]
    (t/is (not= (fingerprint/fingerprint scene)
                (fingerprint/fingerprint (assoc-in scene [:objects uuid/zero :shapes] [b a]))))
    (t/is (not= (fingerprint/fingerprint (assoc scene :extra [1 2]))
                (fingerprint/fingerprint (assoc scene :extra [2 1]))))
    (t/is (not= (fingerprint/fingerprint (assoc scene :extra (into linked-set/empty-linked-set [1 2])))
                (fingerprint/fingerprint (assoc scene :extra (into linked-set/empty-linked-set [2 1])))))
    (t/is (not= (fingerprint/fingerprint (assoc scene :extra (into linked-map/empty-linked-map [[:a 1] [:b 2]])))
                (fingerprint/fingerprint (assoc scene :extra (into linked-map/empty-linked-map [[:b 2] [:a 1]])))))
    (t/is (not= (fingerprint/fingerprint (assoc scene :extra (point/point 1 2)))
                (fingerprint/fingerprint (assoc scene :extra {:x 1 :y 2}))))))

(t/deftest unrepresentable-transit-content-never-gets-a-misleading-fingerprint
  (let [scene (starting-snapshot)]
    (t/is (= ::codec/unrepresentable-value
             (try
               (fingerprint/fingerprint (assoc scene :extra {"__proto__" "value"}))
               nil
               (catch :default cause (:type (ex-data cause))))))))

(t/deftest objects-map-materializes-before-hashing-and-production-tags-survive
  (let [scene (starting-snapshot)
        wrapped (update scene :objects objects-map/wrap)
        data (assoc scene :extra {:point (point/point 1 2)
                                  :matrix (matrix/matrix)
                                  :path (path/path-data [{:command :move-to :params {:x 1 :y 2}}
                                                         {:command :line-to :params {:x 3 :y 4}}])})
        text (fingerprint/snapshot-text data)]
    (t/is (= (fingerprint/fingerprint scene) (fingerprint/fingerprint wrapped)))
    (t/is (not (str/includes? (fingerprint/snapshot-text wrapped) "penpot/objects-map")))
    (t/is (str/includes? text "~#shape"))
    (t/is (str/includes? text "~#point"))
    (t/is (str/includes? text "~#matrix"))
    (t/is (str/includes? text "~#penpot/path-data"))
    (t/is (= (fingerprint/fingerprint data)
             (fingerprint/fingerprint (transit/decode-str (transit/encode-str data)))))))

(t/deftest digest-has-a-fixed-encoding-version-and-known-sha256-vector
  ;; The verbose Transit map for {:objects {} :refs {}} is canonical text.
  (t/is (= "{\"~:objects\":{},\"~:refs\":{}}"
           (fingerprint/snapshot-text {:objects {} :refs {}})))
  (let [fp (fingerprint/fingerprint {:objects {} :refs {}})]
    (t/is (= 1 (:encoding-version fp)))
    (t/is (= :sha-256 (:algorithm fp)))
    (t/is (= "ceb57067506683f5a0e80615bc9dd6f6d16325e333864da705fa4916fbdfe52d"
             (:digest fp)))))
