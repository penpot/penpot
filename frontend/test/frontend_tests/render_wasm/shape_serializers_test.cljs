;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.shape-serializers-test
  "Unit tests for the WASM heap discipline of shape serializers used by
   calculate-bool (issue #10647 hardening).

   Emscripten replaces module.HEAPU8/HEAPU32 with fresh views when
   linear memory grows, so a view captured before an allocating wasm
   call goes stale and writes through it are lost. Additionally, the
   Rust staging buffer (_alloc_bytes) must always be consumed or freed:
   a leaked buffer makes the NEXT _alloc_bytes anywhere in the app fail
   with 'Bytes already allocated'.

   Covered here:
   - set-shape-path-content: every chunk must be written through a heap
     view fetched AFTER that chunk's _alloc_bytes.
   - set-shape-strokes: the fill payload must be written through a view
     created AFTER the _add_shape_*_stroke call (which can grow memory),
     and a stroke without any fill must not stage a buffer at all."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.types.path.impl :as path.impl]
   [app.common.uuid :as uuid]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.wasm :as wasm]
   [cljs.test :as t :include-macros true]))

;; 256KiB backing buffer: one full path chunk fits exactly
(def ^:private HEAP-U8-LEN (* 256 1024))

(defn- install-heap!
  "(Re)creates the module heap views over a fresh backing buffer,
   the way Emscripten does after memory growth."
  [module]
  (let [buffer (js/ArrayBuffer. HEAP-U8-LEN)]
    (unchecked-set module "HEAPU8" (js/Uint8Array. buffer))
    (unchecked-set module "HEAPU32" (js/Uint32Array. buffer))
    (unchecked-set module "HEAPF32" (js/Float32Array. buffer))))

(defn- with-module*
  [module thunk]
  (let [orig wasm/internal-module]
    (set! wasm/internal-module module)
    (try
      (thunk)
      (finally
        (set! wasm/internal-module orig)))))

(defn- path-data->u32
  [pd]
  (let [^js dview (.-buffer pd)]
    (js/Uint32Array. (.-buffer dview)
                     (.-byteOffset dview)
                     (/ (.-byteLength dview) 4))))

(defn- big-path-content
  "A path large enough to need more than one 256KiB upload chunk."
  [segment-count]
  (path.impl/from-plain
   (into [{:command :move-to :params {:x 0 :y 0}}]
         (map (fn [i] {:command :line-to :params {:x i :y (inc i)}}))
         (range (dec segment-count)))))

;; ---------------------------------------------------------------------------
;; set-shape-path-content
;; ---------------------------------------------------------------------------

(defn- make-path-module
  "Fake module for set-shape-path-content. Every _alloc_bytes grows the
   heap (replaces the views); _set_shape_path_chunk_buffer reads the
   chunk from the CURRENT view, like the wasm side does."
  []
  (let [staged?     (atom false)
        alloc-sizes (atom [])
        received    (atom [])
        module      #js {}]
    (install-heap! module)
    (unchecked-set module "_read_error_code" (fn [] 2))
    (unchecked-set module "_start_shape_path_buffer" (fn [] js/undefined))
    (unchecked-set module "_set_shape_path_buffer" (fn [] js/undefined))
    (unchecked-set module "_alloc_bytes"
                   (fn [size]
                     (when @staged?
                       (throw (js/Error. "Bytes already allocated")))
                     (reset! staged? true)
                     (swap! alloc-sizes conj size)
                     ;; growth on every allocation: old views go stale
                     (install-heap! module)
                     0))
    (unchecked-set module "_set_shape_path_chunk_buffer"
                   (fn []
                     (reset! staged? false)
                     (let [heap  (unchecked-get module "HEAPU32")
                           words (/ (peek @alloc-sizes) 4)]
                       (swap! received into (vec (.slice ^js heap 0 words))))
                     js/undefined))
    {:module module :staged? staged? :alloc-sizes alloc-sizes :received received}))

(t/deftest path-chunks-written-through-current-heap-view
  "Each chunk's bytes must land in the heap view that is current after
   that chunk's allocation; a view captured before the loop loses every
   chunk once memory grows."
  (let [content  (big-path-content 9500)
        expected (vec (path-data->u32 content))
        {:keys [module staged? alloc-sizes received]} (make-path-module)]
    (with-module* module
      (fn []
        (wasm.api/set-shape-path-content content)
        (t/is (= 2 (count @alloc-sizes)) "the content needs two chunks")
        (t/is (= expected @received)
              "wasm received exactly the content bytes across all chunks")
        (t/is (false? @staged?) "no staged buffer left behind")))))

;; ---------------------------------------------------------------------------
;; set-shape-strokes
;; ---------------------------------------------------------------------------

(defn- make-strokes-module
  "Fake module for set-shape-strokes. _add_shape_*_stroke grows the heap
   (replaces the views); _add_shape_stroke_fill consumes the staged fill
   payload from the CURRENT view."
  []
  (let [staged?     (atom false)
        alloc-sizes (atom [])
        fills       (atom [])
        module      #js {}]
    (install-heap! module)
    (unchecked-set module "_read_error_code" (fn [] 2))
    (unchecked-set module "_clear_shape_strokes" (fn [] js/undefined))
    (unchecked-set module "_is_image_cached" (fn [_a _b _c _d _t] 1))
    (doseq [entry ["_add_shape_inner_stroke" "_add_shape_outer_stroke" "_add_shape_center_stroke"]]
      (unchecked-set module entry
                     (fn [_w _s _cs _ce _d _g]
                       ;; pushing a stroke allocates: memory can grow here
                       (install-heap! module)
                       js/undefined)))
    (unchecked-set module "_alloc_bytes"
                   (fn [size]
                     (when @staged?
                       (throw (js/Error. "Bytes already allocated")))
                     (reset! staged? true)
                     (swap! alloc-sizes conj size)
                     0))
    (unchecked-set module "_add_shape_stroke_fill"
                   (fn []
                     (reset! staged? false)
                     (let [heap (unchecked-get module "HEAPU8")]
                       (swap! fills conj (vec (.slice ^js heap 0 types.fills.impl/FILL-U8-SIZE))))
                     js/undefined))
    {:module module :staged? staged? :alloc-sizes alloc-sizes :fills fills}))

(t/deftest stroke-fill-written-through-current-heap-view
  "The fill payload must be written through a view created after the
   stroke-push wasm call: that call can grow memory, and a stale view
   silently loses the payload (wasm then reads zeros)."
  (let [{:keys [module staged? fills]} (make-strokes-module)
        stroke {:stroke-color "#fabada"
                :stroke-opacity 1
                :stroke-width 2
                :stroke-alignment :center
                :stroke-style :solid}]
    (with-module* module
      (fn []
        (doall (wasm.api/set-shape-strokes (uuid/next) [stroke] false))
        (t/is (= 1 (count @fills)) "one fill payload uploaded")
        (t/is (some pos? (subvec (first @fills) 4 8))
              "the rgba payload is present in the current heap view")
        (t/is (false? @staged?) "the staged fill buffer was consumed")))))

;; ---------------------------------------------------------------------------
;; propagate-modifiers
;; ---------------------------------------------------------------------------

(defn- make-modifiers-module
  "Fake module for propagate-modifiers. _alloc_bytes and
   _propagate_modifiers both grow the heap (replace the views); the
   result exists only in the view that is current after the call, while
   the pre-call view holds a garbage length at the result offset."
  []
  (let [staged?     (atom false)
        received    (atom nil)
        module      #js {}
        result-off32 128]
    (install-heap! module)
    (unchecked-set module "_read_error_code" (fn [] 2))
    (unchecked-set module "_free_bytes" (fn [] (reset! staged? false) js/undefined))
    (unchecked-set module "_alloc_bytes"
                   (fn [_size]
                     (when @staged?
                       (throw (js/Error. "Bytes already allocated")))
                     (reset! staged? true)
                     ;; growth on allocation: old views go stale
                     (install-heap! module)
                     0))
    (unchecked-set module "_propagate_modifiers"
                   (fn [_pixel-precision]
                     ;; the wasm side reads the entries from CURRENT memory
                     (let [heapu32 (unchecked-get module "HEAPU32")
                           heapf32 (unchecked-get module "HEAPF32")]
                       (reset! received {:uuid   (vec (.slice ^js heapu32 0 4))
                                         :matrix (vec (.slice ^js heapf32 4 10))
                                         :kind   (aget ^js heapu32 10)}))
                     (reset! staged? false)
                     ;; poison the old view at the result offset, then grow:
                     ;; the (empty) result only exists in the new view
                     (aset (unchecked-get module "HEAPU32") result-off32 9999)
                     (install-heap! module)
                     (aset (unchecked-get module "HEAPU32") result-off32 0)
                     (* 4 result-off32)))
    {:module module :staged? staged? :received received}))

(t/deftest propagate-modifiers-heap-discipline
  "The entries must be written through views fetched after the
   allocation, and the result must be read through views fetched after
   the call — both calls can grow memory and detach older views."
  (let [{:keys [module staged? received]} (make-modifiers-module)
        id      (uuid/next)
        entries [[id {:transform (gmt/matrix) :kind :parent}]]]
    (with-module* module
      (fn []
        (let [result (wasm.api/propagate-modifiers entries false)]
          (t/is (= (vec (uuid/get-u32 id)) (:uuid @received))
                "wasm reads the entry uuid from the current view")
          (t/is (= [1 0 0 1 0 0] (:matrix @received))
                "wasm reads the transform from the current view")
          (t/is (number? (:kind @received)))
          (t/is (= [] result)
                "the (empty) result is read from the post-growth view")
          (t/is (false? @staged?) "no staged buffer left behind"))))))

(t/deftest fill-less-stroke-stages-no-buffer
  "A stroke without color/gradient/image has no fill payload to upload:
   it must not allocate a staging buffer that nothing ever consumes
   (a silent leak that fails the next allocation app-wide)."
  (let [{:keys [module staged? alloc-sizes]} (make-strokes-module)
        stroke {:stroke-width 1
                :stroke-alignment :center}]
    (with-module* module
      (fn []
        (doall (wasm.api/set-shape-strokes (uuid/next) [stroke] false))
        (t/is (false? @staged?) "no staged buffer left behind")
        (t/is (empty? @alloc-sizes) "no allocation performed for a fill-less stroke")))))
