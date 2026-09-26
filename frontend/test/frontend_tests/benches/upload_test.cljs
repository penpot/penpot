;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.upload-test
  "Test routing contract for `benches.render-wasm.upload/upload-scene!`.

  Byte layouts are proven by `frontend-tests.render-wasm.serialization-test`
  and `process-objects-test`; this suite pins: the order comes from
  `scenes.common/upload-order`, the batch helper is called exactly once with
  the caller's opts, and the prepared vector is returned in order."
  (:require
   [app.common.render-wasm.api.upload :as upload]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [benches.render-wasm.scenes.common :as common]
   [benches.render-wasm.upload :as bench-upload]
   [cljs.test :as t :include-macros true]))

(defn- sample-snapshot
  "Root frame with two rectangles, so order is observable."
  []
  (let [r1-id (uuid/custom 11 1)
        r2-id (uuid/custom 11 2)
        r1    (cts/setup-shape {:id r1-id :type :rect :name "R1"
                                :x 10 :y 10 :width 100 :height 60
                                :parent-id uuid/zero :frame-id uuid/zero})
        r2    (cts/setup-shape {:id r2-id :type :rect :name "R2"
                                :x 200 :y 40 :width 80 :height 80
                                :parent-id uuid/zero :frame-id uuid/zero})
        root  (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                :x 0 :y 0 :width 1920 :height 1080
                                :parent-id uuid/zero :frame-id uuid/zero
                                :shapes [r1-id r2-id]})]
    {:objects {uuid/zero root, r1-id r1, r2-id r2}
     :refs    {:r1 r1-id :r2 r2-id}}))

(t/deftest upload-scene-uses-snapshot-order-and-caller-opts
  (let [snapshot    (common/validate! (sample-snapshot))
        opts        {:include-layout? false :include-fills-strokes? true}
        flush-calls (atom [])
        orig-flush  upload/flush-shapes-batch!
        expected    (common/upload-order snapshot)]
    (set! upload/flush-shapes-batch!
          (fn [shapes o] (swap! flush-calls conj {:shapes shapes :opts o}) nil))
    (try
      (let [result (bench-upload/upload-scene! snapshot opts)
            flushed (first @flush-calls)]
        (t/testing "exactly one batch upload"
          (t/is (= 1 (count @flush-calls))))
        (t/testing "caller opts pass through"
          (t/is (= opts (:opts flushed))))
        (t/testing "shapes follow snapshot upload order"
          (t/is (= (mapv :id expected) (mapv :id (:shapes flushed)))))
        (t/testing "prepared vector returns in order"
          (t/is (= (mapv :id expected) (mapv :id result)))))
      (finally
        (set! upload/flush-shapes-batch! orig-flush)))))
