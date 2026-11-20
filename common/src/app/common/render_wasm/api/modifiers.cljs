;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.api.modifiers
  "Modifier operations for an initialized WASM renderer.

  Shared by the frontend and the renderer benchmarking code.

  Calls share one payload slot and must run without overlap. The caller owns
  renderer initialization and render scheduling."
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.mem.heap32 :as mem.h32]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]))

(def ^:private ^:const MODIFIER-U8-SIZE 40)
(def ^:private ^:const MODIFIER-U32-SIZE (/ MODIFIER-U8-SIZE 4))
(def ^:private ^:const INPUT-MODIFIER-U8-SIZE 44)
(def ^:private ^:const STRUCTURE-MODIFIER-U8-SIZE 44)

(def ^:private pixel-precision
  {:disabled 0
   :both     1
   :only-x   2
   :only-y   3})

(defn- pixel-precision-mode
  [snap-pixel? snap-ignore-axis]
  (pixel-precision
   (cond
     (not snap-pixel?)       :disabled
     (= :x snap-ignore-axis) :only-y
     (= :y snap-ignore-axis) :only-x
     :else                   :both)))

(defn- get-modifier-entry
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

(defn- get-modifiers
  [pointer]
  (let [offset     (mem/->offset-32 pointer)
        heapu32    (mem/get-heap-u32)
        heapf32    (mem/get-heap-f32)
        length     (aget heapu32 offset)
        start      (inc offset)
        end        (+ start (* length MODIFIER-U32-SIZE))]
    (mapv (partial get-modifier-entry heapu32 heapf32)
          (range start end MODIFIER-U32-SIZE))))

(defn propagate-modifiers
  "Propagates geometry transforms through the shape tree.

  Rounds geometry to the pixel grid when snapping is enabled, except on the
  ignored axis. Requires an initialized renderer and enum bindings.

  Args:
    entries: Counted collection of [uuid {:transform matrix :kind kind}],
      where kind is :parent or :child.
    snap-pixel?: Whether to round geometry to the pixel grid.
    snap-ignore-axis: Axis to leave unsnapped, :x, :y or nil.

  Returns:
    A vector of [uuid matrix] transforms, or [] for empty entries."
  [entries snap-pixel? snap-ignore-axis]
  (if (empty? entries)
    []
    (let [size    (mem/get-alloc-size entries INPUT-MODIFIER-U8-SIZE)
          offset  (mem/alloc->offset-32 size)
          heapf32 (mem/get-heap-f32)
          heapu32 (mem/get-heap-u32)]
      (reduce (fn [offset [id {:keys [transform kind]}]]
                (-> offset
                    (mem.h32/write-uuid heapu32 id)
                    (mem.h32/write-matrix heapf32 transform)
                    (mem.h32/write-u32 heapu32 (sr/translate-transform-entry-kind kind))))
              offset
              entries)
      (let [precision (pixel-precision-mode snap-pixel? snap-ignore-axis)
            pointer   (h/call wasm/internal-module "_propagate_modifiers" precision)]
        (try
          (get-modifiers pointer)
          (finally
            (mem/free)))))))

(defn set-modifiers
  "Sets temporary geometry transforms for rendering.

  Empty modifiers leave the current preview unchanged.

  Args:
    modifiers: Vector of [uuid matrix] transforms."
  [modifiers]
  (when (seq modifiers)
    (let [size    (mem/get-alloc-size modifiers MODIFIER-U8-SIZE)
          offset  (mem/alloc->offset-32 size)
          heapu32 (mem/get-heap-u32)
          heapf32 (mem/get-heap-f32)]
      (reduce (fn [offset [id transform]]
                (-> offset
                    (mem.h32/write-uuid heapu32 id)
                    (mem.h32/write-matrix heapf32 transform)))
              offset
              modifiers)
      (h/call wasm/internal-module "_set_modifiers")))
  nil)

(defn set-structure-modifiers
  "Sets temporary changes to shape structure and content scaling.

  Empty entries leave the current structure modifiers unchanged.

  Args:
    entries: Counted collection of maps with :type, :parent, :id, :index
             and :value. A missing index defaults to zero."
  [entries]
  (when (seq entries)
    (let [size    (mem/get-alloc-size entries STRUCTURE-MODIFIER-U8-SIZE)
          offset  (mem/alloc->offset-32 size)
          heapu32 (mem/get-heap-u32)
          heapf32 (mem/get-heap-f32)]
      (reduce (fn [offset {:keys [type parent id index value]}]
                (-> offset
                    (mem.h32/write-u32 heapu32 (sr/translate-structure-modifier-type type))
                    (mem.h32/write-u32 heapu32 (d/nilv index 0))
                    (mem.h32/write-uuid heapu32 parent)
                    (mem.h32/write-uuid heapu32 id)
                    (mem.h32/write-f32 heapf32 value)))
              offset
              entries)
      (h/call wasm/internal-module "_set_structure_modifiers")))
  nil)

(defn set-modifiers-start
  "Enables interactive rendering for shape transforms."
  []
  (h/call wasm/internal-module "_set_modifiers_start")
  nil)

(defn set-modifiers-end
  "Ends interactive rendering for shape transforms.

  A subsequent render produces the final full-quality image."
  []
  (h/call wasm/internal-module "_set_modifiers_end")
  nil)

(defn clean-modifiers
  "Clears temporary geometry, structure and content-scaling modifiers."
  []
  (h/call wasm/internal-module "_clean_modifiers")
  nil)
