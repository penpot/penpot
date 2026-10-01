;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.render-wasm.upload-encode-test
  (:require
   [app.common.render-wasm.api.upload-encode :as encode]
   [app.common.render-wasm.enums-data :as enums]
   [app.common.render-wasm.serializers.color :as sr-clr]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- sample-rect
  [id parent-id]
  {:id id
   :parent-id parent-id
   :type :rect
   :name "r"
   :frame-id parent-id
   :shapes []
   :opacity 1
   :rotation 0
   :selrect {:x1 0 :y1 0 :x2 100 :y2 50 :x 0 :y 0 :width 100 :height 50}
   :points []
   :fills [{:fill-color "#ff0000" :fill-opacity 1}]
   :strokes []
   :hidden false})

(defn- sample-root
  [child-id]
  {:id uuid/zero
   :type :frame
   :name "Root"
   :parent-id nil
   :frame-id uuid/zero
   :shapes [child-id]
   :selrect {:x1 0 :y1 0 :x2 100 :y2 50 :x 0 :y 0 :width 100 :height 50}
   :fills []
   :strokes []
   :opacity 1
   :rotation 0
   :show-content true
   :hidden false})

(t/deftest enums-shape-type-defaults
  (t/is (= 3 (enums/translate-shape-type :rect)))
  (t/is (= 0 (enums/translate-shape-type :frame)))
  (t/is (= 3 (enums/translate-shape-type :unknown-type)))
  (t/is (= 0 (enums/translate-constraint-h :none)))
  (t/is (= 1 (enums/translate-constraint-h :left))))

(t/deftest encode-empty-batch
  (let [bytes (encode/encode-shapes-batch [] {:include-layout? true
                                              :include-fills-strokes? true})]
    (t/is (= 4 #?(:clj (alength ^bytes bytes)
                  :cljs (.-byteLength bytes))))
    (t/is (= 0 (bit-and #?(:clj (aget ^bytes bytes 0)
                           :cljs (aget bytes 0))
                        0xff)))))

(t/deftest encode-page-objects-smoke
  (let [child-id (uuid/next)
        objects  {uuid/zero (sample-root child-id)
                  child-id  (sample-rect child-id uuid/zero)}
        bytes    (encode/encode-page-objects objects)
        len      #?(:clj (alength ^bytes bytes)
                    :cljs (.-byteLength bytes))]
    (t/is (< 4 len))
    ;; shape_count == 2 (root + rect), little-endian u32 at 0
    (t/is (= 2 (+ #?(:clj (aget ^bytes bytes 0)
                     :cljs (aget bytes 0))
                  (bit-shift-left #?(:clj (aget ^bytes bytes 1)
                                     :cljs (aget bytes 1))
                                  8)
                  (bit-shift-left #?(:clj (aget ^bytes bytes 2)
                                     :cljs (aget bytes 2))
                                  16)
                  (bit-shift-left #?(:clj (aget ^bytes bytes 3)
                                     :cljs (aget bytes 3))
                                  24))))))

(t/deftest hex-u32argb-accepts-double-opacity
  ;; Shadows often carry opacity as a double; JVM bit-shift needs an int.
  (let [c (sr-clr/hex->u32argb "#ff0000" 0.5)]
    (t/is (int? c))
    (t/is (pos? c))))

(t/deftest encode-shape-with-shadow
  (let [child-id (uuid/next)
        rect     (-> (sample-rect child-id uuid/zero)
                     (assoc :shadow
                            [{:id (uuid/next)
                              :style :drop-shadow
                              :color {:color "#000000" :opacity 0.5}
                              :offset-x 2
                              :offset-y 2
                              :blur 4
                              :spread 0
                              :hidden false}]))
        objects  {uuid/zero (sample-root child-id)
                  child-id  rect}
        bytes    (encode/encode-page-objects objects)]
    (t/is (< 4 #?(:clj (alength ^bytes bytes)
                  :cljs (.-byteLength bytes))))))

(defn- u8-at
  [bytes i]
  (bit-and #?(:clj (aget ^bytes bytes i)
              :cljs (aget bytes i))
           0xff))

(t/deftest encode-uuid-matches-wasm-le-u32-quartet
  ;; Canonical UUID bytes a1a2a3a4-b1b2-c1c2-d1d2-d3d4d5d6d7d8, written as
  ;; four LE u32s (same layout CLJS buf/write-uuid / get-u32 emit).
  (let [id #uuid "a1a2a3a4-b1b2-c1c2-d1d2-d3d4d5d6d7d8"
        shape (-> (sample-rect id uuid/zero)
                  (assoc :parent-id uuid/zero
                         :fills []
                         :strokes []))
        rec   (encode/encode-shape-record
               shape
               {:include-layout? false
                :include-fills-strokes? false})
        ;; Record: [u32 payload_len][104 base…]; id starts at byte 4.
        expected [0xa4 0xa3 0xa2 0xa1
                  0xc2 0xc1 0xb2 0xb1
                  0xd4 0xd3 0xd2 0xd1
                  0xd8 0xd7 0xd6 0xd5]]
    (doseq [i (range 16)]
      (t/is (= (nth expected i) (u8-at rec (+ 4 i)))
            (str "uuid byte " i)))))

(t/deftest encode-fill-image-uuid-matches-wasm-le-u32-quartet
  ;; Image fill id must use the same LE layout as shape ids; otherwise WASM
  ;; looks up a swapped id and never binds the host-fetched texture.
  (let [shape-id #uuid "11111111-1111-1111-1111-111111111111"
        image-id #uuid "a1a2a3a4-b1b2-c1c2-d1d2-d3d4d5d6d7d8"
        shape    (-> (sample-rect shape-id uuid/zero)
                     (assoc :parent-id uuid/zero
                            :fills [{:fill-image {:id image-id
                                                  :width 10
                                                  :height 10
                                                  :keep-aspect-ratio false}
                                     :fill-opacity 1}]
                            :strokes []))
        rec      (encode/encode-shape-record
                  shape
                  {:include-layout? false
                   :include-fills-strokes? true})
        ;; [len][base 104][mask][children count=0][fills count][fill…]
        ;; image uuid is at fill_payload + 4.
        fill0    (+ 4 104 4 4 4)
        expected [0xa4 0xa3 0xa2 0xa1
                  0xc2 0xc1 0xb2 0xb1
                  0xd4 0xd3 0xd2 0xd1
                  0xd8 0xd7 0xd6 0xd5]]
    (t/is (= 0x03 (u8-at rec fill0)) "image fill type tag")
    (doseq [i (range 16)]
      (t/is (= (nth expected i) (u8-at rec (+ fill0 4 i)))
            (str "fill-image uuid byte " i)))))

(t/deftest encode-text-shape-type-and-grow
  (let [id    (uuid/next)
        shape {:id id
               :parent-id uuid/zero
               :type :text
               :name "t"
               :frame-id uuid/zero
               :shapes []
               :grow-type :auto-width
               :selrect {:x1 0 :y1 0 :x2 40 :y2 20 :x 0 :y 0 :width 40 :height 20}
               :fills []
               :strokes []
               :opacity 1
               :rotation 0
               :hidden false}
        rec   (encode/encode-shape-record
               shape
               {:include-layout? true
                :include-fills-strokes? true})]
    ;; shape_type at base offset 32 → record byte 4+32
    (t/is (= 5 (u8-at rec (+ 4 32))))
    (t/is (= 1 (enums/translate-grow-type :auto-width)))))
