;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.render-wasm.deserializers
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]))

(defn read-modifier-entry
  [heapu32 heapf32 offset]
  (let [id1 (aget heapu32 (+ offset 0))
        id2 (aget heapu32 (+ offset 1))
        id3 (aget heapu32 (+ offset 2))
        id4 (aget heapu32 (+ offset 3))

        a   (aget heapf32 (+ offset 4))
        b   (aget heapf32 (+ offset 5))
        c   (aget heapf32 (+ offset 6))
        d   (aget heapf32 (+ offset 7))
        e   (aget heapf32 (+ offset 8))
        f   (aget heapf32 (+ offset 9))]

    (d/vec2 (uuid/from-unsigned-parts id1 id2 id3 id4)
            (gmt/matrix a b c d e f))))


(defn read-selection-rect
  [heapf32 offset]
  (let [width  (aget heapf32 (+ offset 0))
        height (aget heapf32 (+ offset 1))
        cx     (aget heapf32 (+ offset 2))
        cy     (aget heapf32 (+ offset 3))
        a      (aget heapf32 (+ offset 4))
        b      (aget heapf32 (+ offset 5))
        c      (aget heapf32 (+ offset 6))
        d      (aget heapf32 (+ offset 7))
        e      (aget heapf32 (+ offset 8))
        f      (aget heapf32 (+ offset 9))]
    {:width width
     :height height
     :center (gpt/point cx cy)
     :transform (gmt/matrix a b c d e f)}))


