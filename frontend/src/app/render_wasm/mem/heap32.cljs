;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.mem.heap32
  "A memory write helpers that uses 32 bits addressed offsets."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]))

(defn write-u32
  [offset heap value]
  (assert (instance? js/Uint32Array heap) "expected Uint32Array instance for `heap`")
  (aset heap offset value)
  (inc offset))

(defn write-f32
  [offset heap value]
  (assert (instance? js/Float32Array heap) "expected Float32Array instance for `heap`")
  (aset heap offset value)
  (inc offset))

(defn write-uuid
  "Write a uuid to 32 bits addressed heap and return the offset
  after write."
  [offset heap id]
  (assert (instance? js/Uint32Array heap) "expected Uint32Array instance for `heap`")
  (let [buffer (uuid/get-u32 id)]
    (.set heap buffer offset)
    (+ offset 4)))

(defn write-matrix
  "Write a matrix to 32 bits addressed heap and return the offset
  after write."
  [offset heap matrix]
  (assert (instance? js/Float32Array heap) "expected Float32Array instance for `heap`")
  (let [a (dm/get-prop matrix :a)
        b (dm/get-prop matrix :b)
        c (dm/get-prop matrix :c)
        d (dm/get-prop matrix :d)
        e (dm/get-prop matrix :e)
        f (dm/get-prop matrix :f)]
    (aset heap (+ offset 0) a)
    (aset heap (+ offset 1) b)
    (aset heap (+ offset 2) c)
    (aset heap (+ offset 3) d)
    (aset heap (+ offset 4) e)
    (aset heap (+ offset 5) f)
    (+ offset 6)))
