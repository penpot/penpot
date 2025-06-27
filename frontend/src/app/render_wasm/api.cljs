;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api
  "A WASM based render API"
  (:require
   ["react-dom/server" :as rds]
   [app.common.data :as d :refer [not-empty?]]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.fill :as types.fill]
   [app.common.types.path :as path]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.render :as render]
   [app.render-wasm.api.fonts :as f]
   [app.render-wasm.api.texts :as t]
   [app.render-wasm.deserializers :as dr]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.performance :as perf]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.serializers.fills :as sr-fills]
   [app.render-wasm.wasm :as wasm]
   [app.util.debug :as dbg]
   [app.util.functions :as fns]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; (defonce internal-frame-id nil)
;; (defonce wasm/internal-module #js {})
(defonce use-dpr? (contains? cf/flags :render-wasm-dpr))

;;
;; List of common entry sizes.
;;
;; All of these entries are in bytes so we need to adjust
;; these values to work with TypedArrays of 32 bits.
;;
(def CHILD-ENTRY-SIZE 16)
(def MODIFIER-ENTRY-SIZE 40)
(def MODIFIER-ENTRY-TRANSFORM-OFFSET 16)
(def GRID-LAYOUT-ROW-ENTRY-SIZE 5)
(def GRID-LAYOUT-COLUMN-ENTRY-SIZE 5)
(def GRID-LAYOUT-CELL-ENTRY-SIZE 37)

(defn modifier-get-entries-size
  "Returns the list of a modifier list in bytes"
  [modifiers]
  (mem/get-list-size modifiers MODIFIER-ENTRY-SIZE))

(defn grid-layout-get-row-entries-size
  [rows]
  (mem/get-list-size rows GRID-LAYOUT-ROW-ENTRY-SIZE))

(defn grid-layout-get-column-entries-size
  [columns]
  (mem/get-list-size columns GRID-LAYOUT-COLUMN-ENTRY-SIZE))

(defn grid-layout-get-cell-entries-size
  [cells]
  (mem/get-list-size cells GRID-LAYOUT-CELL-ENTRY-SIZE))

(def dpr
  (if use-dpr? (if (exists? js/window) js/window.devicePixelRatio 1.0) 1.0))

;; Based on app.main.render/object-svg
(mf/defc object-svg
  {::mf/props :obj}
  [{:keys [shape] :as props}]
  (let [objects (mf/deref refs/workspace-page-objects)
        shape-wrapper
        (mf/with-memo [shape]
          (render/shape-wrapper-factory objects))]

    [:svg {:version "1.1"
           :xmlns "http://www.w3.org/2000/svg"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :fill "none"}
     [:& shape-wrapper {:shape shape}]]))

(defn get-static-markup
  [shape]
  (->
   (mf/element object-svg #js {:shape shape})
   (rds/renderToStaticMarkup)))

;; This should never be called from the outside.
(defn- render
  [timestamp]
  (h/call wasm/internal-module "_render" timestamp)
  (set! wasm/internal-frame-id nil))

(def debounce-render (fns/debounce render 100))

(defn cancel-render
  [_]
  (when wasm/internal-frame-id
    (js/cancelAnimationFrame wasm/internal-frame-id)
    (set! wasm/internal-frame-id nil)))

(defn request-render
  [requester]
  (when wasm/internal-frame-id (cancel-render requester))
  (let [frame-id (js/requestAnimationFrame render)]
    (set! wasm/internal-frame-id frame-id)))

(defn use-shape
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call wasm/internal-module "_use_shape"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-parent-id
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call wasm/internal-module "_set_parent"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-shape-clip-content
  [clip-content]
  (h/call wasm/internal-module "_set_shape_clip_content" clip-content))

(defn set-shape-type
  [type]
  (h/call wasm/internal-module "_set_shape_type" (sr/translate-shape-type type)))

(defn set-masked
  [masked]
  (h/call wasm/internal-module "_set_shape_masked_group" masked))

(defn set-shape-selrect
  [selrect]
  (h/call wasm/internal-module "_set_shape_selrect"
          (dm/get-prop selrect :x1)
          (dm/get-prop selrect :y1)
          (dm/get-prop selrect :x2)
          (dm/get-prop selrect :y2)))

(defn set-shape-transform
  [transform]
  (h/call wasm/internal-module "_set_shape_transform"
          (dm/get-prop transform :a)
          (dm/get-prop transform :b)
          (dm/get-prop transform :c)
          (dm/get-prop transform :d)
          (dm/get-prop transform :e)
          (dm/get-prop transform :f)))

(defn set-shape-rotation
  [rotation]
  (h/call wasm/internal-module "_set_shape_rotation" rotation))

(defn set-shape-children
  [shape-ids]
  (let [num-shapes (count shape-ids)]
    (perf/begin-measure "set-shape-children")
    (when (> num-shapes 0)
      (let [offset (mem/alloc-bytes (* CHILD-ENTRY-SIZE num-shapes))
            heap (mem/get-heap-u32)]

        (loop [entries (seq shape-ids)
               current-offset  offset]
          (when-not (empty? entries)
            (let [id (first entries)]
              (sr/heapu32-set-uuid id heap (mem/ptr8->ptr32 current-offset))
              (recur (rest entries) (+ current-offset CHILD-ENTRY-SIZE)))))))

    (let [result (h/call wasm/internal-module "_set_children")]
      (perf/end-measure "set-shape-children")
      result)))

(defn- get-string-length [string] (+ (count string) 1))

(defn- fetch-image
  [shape-id image-id]
  (let [buffer-shape-id (uuid/get-u32 shape-id)
        buffer-image-id (uuid/get-u32 image-id)
        url             (cf/resolve-file-media {:id image-id})]
    {:key url
     :callback #(->> (http/send! {:method :get
                                  :uri url
                                  :response-type :blob})
                     (rx/map :body)
                     (rx/mapcat wapi/read-file-as-array-buffer)
                     (rx/map (fn [image]
                               (let [size    (.-byteLength image)
                                     offset  (mem/alloc-bytes size)
                                     heap    (mem/get-heap-u8)
                                     data    (js/Uint8Array. image)]
                                 (.set heap data offset)
                                 (h/call wasm/internal-module "_store_image"
                                         (aget buffer-shape-id 0)
                                         (aget buffer-shape-id 1)
                                         (aget buffer-shape-id 2)
                                         (aget buffer-shape-id 3)
                                         (aget buffer-image-id 0)
                                         (aget buffer-image-id 1)
                                         (aget buffer-image-id 2)
                                         (aget buffer-image-id 3))
                                 true))))}))

(defn- get-fill-images
  [leaf]
  (filter :fill-image (:fills leaf)))

(defn- process-fill-image
  [shape-id fill]
  (when-let [image (:fill-image fill)]
    (let [id (dm/get-prop image :id)
          buffer (uuid/get-u32 id)
          cached-image? (h/call wasm/internal-module "_is_image_cached"
                                (aget buffer 0)
                                (aget buffer 1)
                                (aget buffer 2)
                                (aget buffer 3))]
      (when (zero? cached-image?)
        (fetch-image shape-id id)))))

(defn set-shape-text-images
  [shape-id content]

  (let [paragraph-set (first (get content :children))
        paragraphs (get paragraph-set :children)]
    (->> paragraphs
         (mapcat :children)
         (mapcat get-fill-images)
         (map #(process-fill-image shape-id %)))))

(defn set-shape-fills
  [shape-id fills]
  (if (empty? fills)
    (h/call wasm/internal-module "_clear_shape_fills")
    (let [fills (take types.fill/MAX-FILLS fills)
          image-fills (filter :fill-image fills)
          offset (mem/alloc-bytes (* (count fills) sr-fills/FILL-BYTE-SIZE))
          heap (mem/get-heap-u8)
          dview (js/DataView. (.-buffer heap))]

      ;; write fill data to heap
      (loop [fills (seq fills)
             current-offset offset]
        (when-not (empty? fills)
          (let [fill       (first fills)
                new-offset (sr-fills/write-fill! current-offset dview fill)]
            (recur (rest fills) new-offset))))

      ;; send fills to wasm
      (h/call wasm/internal-module "_set_shape_fills")

      ;; load images for image fills if not cached
      (keep (fn [fill]
              (let [image         (:fill-image fill)
                    id            (dm/get-prop image :id)
                    buffer        (uuid/get-u32 id)
                    cached-image? (h/call wasm/internal-module "_is_image_cached"
                                          (aget buffer 0)
                                          (aget buffer 1)
                                          (aget buffer 2)
                                          (aget buffer 3))]
                (when (zero? cached-image?)
                  (fetch-image shape-id id))))
            image-fills))))

(defn set-shape-strokes
  [shape-id strokes]
  (h/call wasm/internal-module "_clear_shape_strokes")
  (keep (fn [stroke]
          (let [opacity   (or (:stroke-opacity stroke) 1.0)
                color     (:stroke-color stroke)
                gradient  (:stroke-color-gradient stroke)
                image     (:stroke-image stroke)
                width     (:stroke-width stroke)
                align     (:stroke-alignment stroke)
                style     (-> stroke :stroke-style sr/translate-stroke-style)
                cap-start (-> stroke :stroke-cap-start sr/translate-stroke-cap)
                cap-end   (-> stroke :stroke-cap-end sr/translate-stroke-cap)
                offset    (mem/alloc-bytes sr-fills/FILL-BYTE-SIZE)
                heap      (mem/get-heap-u8)
                dview     (js/DataView. (.-buffer heap))]
            (case align
              :inner (h/call wasm/internal-module "_add_shape_inner_stroke" width style cap-start cap-end)
              :outer (h/call wasm/internal-module "_add_shape_outer_stroke" width style cap-start cap-end)
              (h/call wasm/internal-module "_add_shape_center_stroke" width style cap-start cap-end))

            (cond
              (some? gradient)
              (do
                (sr-fills/write-gradient-fill! offset dview gradient opacity)
                (h/call wasm/internal-module "_add_shape_stroke_fill"))

              (some? image)
              (let [image-id      (dm/get-prop image :id)
                    buffer        (uuid/get-u32 image-id)
                    cached-image? (h/call wasm/internal-module "_is_image_cached" (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))]
                (sr-fills/write-image-fill! offset dview image-id opacity
                                            (dm/get-prop image :width)
                                            (dm/get-prop image :height))
                (h/call wasm/internal-module "_add_shape_stroke_fill")
                (when (== cached-image? 0)
                  (fetch-image shape-id image-id)))

              (some? color)
              (do
                (sr-fills/write-solid-fill! offset dview (sr-clr/hex->u32argb color opacity))
                (h/call wasm/internal-module "_add_shape_stroke_fill")))))
        strokes))

(defn set-shape-path-attrs
  [attrs]
  (let [style (:style attrs)
        attrs (-> attrs
                  (dissoc :style)
                  (merge style))
        str   (sr/serialize-path-attrs attrs)
        size  (count str)
        offset   (mem/alloc-bytes size)]
    (h/call wasm/internal-module "stringToUTF8" str offset size)
    (h/call wasm/internal-module "_set_shape_path_attrs" (count attrs))))

;; FIXME: revisit on heap refactor is merged to use u32 instead u8
(defn set-shape-path-content
  [content]
  (let [pdata  (path/content content)
        size   (path/get-byte-size content)
        offset (mem/alloc-bytes size)
        heap   (mem/get-heap-u8)]
    (path/write-to pdata (.-buffer heap) offset)
    (h/call wasm/internal-module "_set_shape_path_content")))

(defn set-shape-svg-raw-content
  [content]
  (let [size (get-string-length content)
        offset (mem/alloc-bytes size)]
    (h/call wasm/internal-module "stringToUTF8" content offset size)
    (h/call wasm/internal-module "_set_shape_svg_raw_content")))

(defn set-shape-blend-mode
  [blend-mode]
  ;; These values correspond to skia::BlendMode representation
  ;; https://rust-skia.github.io/doc/skia_safe/enum.BlendMode.html
  (h/call wasm/internal-module "_set_shape_blend_mode" (sr/translate-blend-mode blend-mode)))

(defn set-shape-vertical-align
  [vertical-align]
  (h/call wasm/internal-module "_set_shape_vertical_align" (sr/serialize-vertical-align vertical-align)))

(defn set-shape-opacity
  [opacity]
  (h/call wasm/internal-module "_set_shape_opacity" (or opacity 1)))

(defn set-constraints-h
  [constraint]
  (when constraint
    (h/call wasm/internal-module "_set_shape_constraint_h" (sr/translate-constraint-h constraint))))

(defn set-constraints-v
  [constraint]
  (when constraint
    (h/call wasm/internal-module "_set_shape_constraint_v" (sr/translate-constraint-v constraint))))

(defn set-shape-hidden
  [hidden]
  (h/call wasm/internal-module "_set_shape_hidden" hidden))

(defn set-shape-bool-type
  [bool-type]
  (h/call wasm/internal-module "_set_shape_bool_type" (sr/translate-bool-type bool-type)))

(defn- translate-blur-type
  [blur-type]
  (case blur-type
    :layer-blur 1
    0))

(defn set-shape-blur
  [blur]
  (let [type   (-> blur :type sr/translate-blur-type)
        hidden (:hidden blur)
        value  (:value blur)]
    (h/call wasm/internal-module "_set_shape_blur" type hidden value)))

(defn set-shape-corners
  [corners]
  (let [r1 (or (get corners 0) 0)
        r2 (or (get corners 1) 0)
        r3 (or (get corners 2) 0)
        r4 (or (get corners 3) 0)]
    (h/call wasm/internal-module "_set_shape_corners" r1 r2 r3 r4)))

(defn set-flex-layout
  [shape]
  (let [dir (-> (or (dm/get-prop shape :layout-flex-dir) :row) sr/translate-layout-flex-dir)
        gap (dm/get-prop shape :layout-gap)
        row-gap (or (dm/get-prop gap :row-gap) 0)
        column-gap (or (dm/get-prop gap :column-gap) 0)

        align-items (-> (or (dm/get-prop shape :layout-align-items) :start) sr/translate-layout-align-items)
        align-content (-> (or (dm/get-prop shape :layout-align-content) :stretch) sr/translate-layout-align-content)
        justify-items (-> (or (dm/get-prop shape :layout-justify-items) :start) sr/translate-layout-justify-items)
        justify-content (-> (or (dm/get-prop shape :layout-justify-content) :stretch) sr/translate-layout-justify-content)
        wrap-type (-> (or (dm/get-prop shape :layout-wrap-type) :nowrap) sr/translate-layout-wrap-type)

        padding (dm/get-prop shape :layout-padding)
        padding-top (or (dm/get-prop padding :p1) 0)
        padding-right (or (dm/get-prop padding :p2) 0)
        padding-bottom (or (dm/get-prop padding :p3) 0)
        padding-left (or (dm/get-prop padding :p4) 0)]
    (h/call wasm/internal-module
            "_set_flex_layout_data"
            dir
            row-gap
            column-gap
            align-items
            align-content
            justify-items
            justify-content
            wrap-type
            padding-top
            padding-right
            padding-bottom
            padding-left)))

(defn set-grid-layout-data
  [shape]
  (let [dir (-> (or (dm/get-prop shape :layout-grid-dir) :row) sr/translate-layout-grid-dir)
        gap (dm/get-prop shape :layout-gap)
        row-gap (or (dm/get-prop gap :row-gap) 0)
        column-gap (or (dm/get-prop gap :column-gap) 0)

        align-items (-> (or (dm/get-prop shape :layout-align-items) :start) sr/translate-layout-align-items)
        align-content (-> (or (dm/get-prop shape :layout-align-content) :stretch) sr/translate-layout-align-content)
        justify-items (-> (or (dm/get-prop shape :layout-justify-items) :start) sr/translate-layout-justify-items)
        justify-content (-> (or (dm/get-prop shape :layout-justify-content) :stretch) sr/translate-layout-justify-content)

        padding (dm/get-prop shape :layout-padding)
        padding-top (or (dm/get-prop padding :p1) 0)
        padding-right (or (dm/get-prop padding :p2) 0)
        padding-bottom (or (dm/get-prop padding :p3) 0)
        padding-left (or (dm/get-prop padding :p4) 0)]

    (h/call wasm/internal-module
            "_set_grid_layout_data"
            dir
            row-gap
            column-gap
            align-items
            align-content
            justify-items
            justify-content
            padding-top
            padding-right
            padding-bottom
            padding-left)))

(defn set-grid-layout-rows
  [entries]
  (let [size (grid-layout-get-row-entries-size entries)
        offset (mem/alloc-bytes size)

        heap
        (js/Uint8Array.
         (.-buffer (mem/get-heap-u8))
         offset
         size)]
    (loop [entries (seq entries)
           current-offset  0]
      (when-not (empty? entries)
        (let [{:keys [type value]} (first entries)]
          (.set heap (sr/u8 (sr/translate-grid-track-type type)) (+ current-offset 0))
          (.set heap (sr/f32->u8 value) (+ current-offset 1))
          (recur (rest entries) (+ current-offset GRID-LAYOUT-ROW-ENTRY-SIZE)))))
    (h/call wasm/internal-module "_set_grid_rows")))

(defn set-grid-layout-columns
  [entries]
  (let [size (grid-layout-get-column-entries-size entries)
        offset (mem/alloc-bytes size)

        heap
        (js/Uint8Array.
         (.-buffer (mem/get-heap-u8))
         offset
         size)]
    (loop [entries (seq entries)
           current-offset  0]
      (when-not (empty? entries)
        (let [{:keys [type value]} (first entries)]
          (.set heap (sr/u8 (sr/translate-grid-track-type type)) (+ current-offset 0))
          (.set heap (sr/f32->u8 value) (+ current-offset 1))
          (recur (rest entries) (+ current-offset GRID-LAYOUT-COLUMN-ENTRY-SIZE)))))
    (h/call wasm/internal-module "_set_grid_columns")))

(defn set-grid-layout-cells
  [cells]
  (let [entries (vals cells)
        size (grid-layout-get-cell-entries-size entries)
        offset (mem/alloc-bytes size)

        heap
        (js/Uint8Array.
         (.-buffer (mem/get-heap-u8))
         offset
         size)]

    (loop [entries (seq entries)
           current-offset  0]
      (when-not (empty? entries)
        (let [cell (first entries)]

          ;; row: [u8; 4],
          (.set heap (sr/i32->u8 (:row cell)) (+ current-offset 0))

          ;; row_span: [u8; 4],
          (.set heap (sr/i32->u8 (:row-span cell)) (+ current-offset 4))

          ;; column: [u8; 4],
          (.set heap (sr/i32->u8 (:column cell)) (+ current-offset 8))

          ;; column_span: [u8; 4],
          (.set heap (sr/i32->u8 (:column-span cell)) (+ current-offset 12))

          ;; has_align_self: u8,
          (.set heap (sr/bool->u8 (some? (:align-self cell))) (+ current-offset 16))

          ;; align_self: u8,
          (.set heap (sr/u8 (sr/translate-align-self (:align-self cell))) (+ current-offset 17))

          ;; has_justify_self: u8,
          (.set heap (sr/bool->u8 (some? (:justify-self cell))) (+ current-offset 18))

          ;; justify_self: u8,
          (.set heap (sr/u8 (sr/translate-justify-self (:justify-self cell))) (+ current-offset 19))

          ;; has_shape_id: u8,
          (.set heap (sr/bool->u8 (d/not-empty? (:shapes cell))) (+ current-offset 20))

          ;; shape_id_a: [u8; 4],
          ;; shape_id_b: [u8; 4],
          ;; shape_id_c: [u8; 4],
          ;; shape_id_d: [u8; 4],
          (.set heap (sr/uuid->u8 (or (-> cell :shapes first) uuid/zero)) (+ current-offset 21))

          (recur (rest entries) (+ current-offset GRID-LAYOUT-CELL-ENTRY-SIZE)))))

    (h/call wasm/internal-module "_set_grid_cells")))

(defn set-grid-layout
  [shape]
  (set-grid-layout-data shape)
  (set-grid-layout-rows (:layout-grid-rows shape))
  (set-grid-layout-columns (:layout-grid-columns shape))
  (set-grid-layout-cells (:layout-grid-cells shape)))

(defn set-layout-child
  [shape]
  (let [margins (dm/get-prop shape :layout-item-margin)
        margin-top (or (dm/get-prop margins :m1) 0)
        margin-right (or (dm/get-prop margins :m2) 0)
        margin-bottom (or (dm/get-prop margins :m3) 0)
        margin-left (or (dm/get-prop margins :m4) 0)

        h-sizing (-> (dm/get-prop shape :layout-item-h-sizing) (or :fix) sr/translate-layout-sizing)
        v-sizing (-> (dm/get-prop shape :layout-item-v-sizing) (or :fix) sr/translate-layout-sizing)
        align-self (-> (dm/get-prop shape :layout-item-align-self) sr/translate-align-self)

        max-h (dm/get-prop shape :layout-item-max-h)
        has-max-h (some? max-h)
        min-h (dm/get-prop shape :layout-item-min-h)
        has-min-h (some? min-h)
        max-w (dm/get-prop shape :layout-item-max-w)
        has-max-w (some? max-w)
        min-w (dm/get-prop shape :layout-item-min-w)
        has-min-w (some? min-w)
        is-absolute (boolean (dm/get-prop shape :layout-item-absolute))
        z-index (-> (dm/get-prop shape :layout-item-z-index) (or 0))]
    (h/call wasm/internal-module
            "_set_layout_child_data"
            margin-top
            margin-right
            margin-bottom
            margin-left
            h-sizing
            v-sizing
            has-max-h
            (or max-h 0)
            has-min-h
            (or min-h 0)
            has-max-w
            (or max-w 0)
            has-min-w
            (or min-w 0)
            (some? align-self)
            (or align-self 0)
            is-absolute
            z-index)))

(defn set-shape-shadows
  [shadows]
  (h/call wasm/internal-module "_clear_shape_shadows")
  (let [total-shadows (count shadows)]
    (loop [index 0]
      (when (< index total-shadows)
        (let [shadow (nth shadows index)
              color (dm/get-prop shadow :color)
              blur (dm/get-prop shadow :blur)
              rgba (sr-clr/hex->u32argb (dm/get-prop color :color) (dm/get-prop color :opacity))
              hidden (dm/get-prop shadow :hidden)
              x (dm/get-prop shadow :offset-x)
              y (dm/get-prop shadow :offset-y)
              spread (dm/get-prop shadow :spread)
              style (dm/get-prop shadow :style)]
          (h/call wasm/internal-module "_add_shape_shadow" rgba blur spread x y (sr/translate-shadow-style style) hidden)
          (recur (inc index)))))))

(declare propagate-apply)

(defn set-shape-text-content
  [shape-id content]
  (h/call wasm/internal-module "_clear_shape_text")
  (set-shape-vertical-align (dm/get-prop content :vertical-align))

  (let [paragraph-set (first (dm/get-prop content :children))
        paragraphs (dm/get-prop paragraph-set :children)
        fonts (fonts/get-content-fonts content)
        emoji? (atom false)
        languages (atom #{})]
    (loop [index 0]
      (when (< index (count paragraphs))
        (let [paragraph (nth paragraphs index)
              leaves (dm/get-prop paragraph :children)]
          (when (seq leaves)
            (let [text (apply str (map :text leaves))]
              (when (and (not @emoji?) (t/contains-emoji? text))
                (reset! emoji? true))
              (swap! languages into (t/get-languages text))
              (t/write-shape-text leaves paragraph text))
            (recur (inc index))))))

    (let [updated-fonts
          (-> fonts
              (cond-> @emoji? (f/add-emoji-font))
              (f/add-noto-fonts @languages))]
      (f/store-fonts shape-id updated-fonts))))

(defn set-shape-text
  [shape-id content]
  (concat
   (set-shape-text-images shape-id content)
   (set-shape-text-content shape-id content)))

(defn set-shape-grow-type
  [grow-type]
  (h/call wasm/internal-module "_set_shape_grow_type" (sr/translate-grow-type grow-type)))

(defn text-dimensions
  ([id]
   (use-shape id)
   (text-dimensions))
  ([]
   (let [offset (h/call wasm/internal-module "_get_text_dimensions")
         heapf32 (mem/get-heap-f32)
         width (aget heapf32 (mem/ptr8->ptr32 offset))
         height (aget heapf32 (mem/ptr8->ptr32 (+ offset 4)))
         max-width (aget heapf32 (mem/ptr8->ptr32 (+ offset 8)))]
     (h/call wasm/internal-module "_free_bytes")
     {:width width :height height :max-width max-width})))

(defn set-view-box
  [zoom vbox]
  (h/call wasm/internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
  (h/call wasm/internal-module "_render_from_cache")
  (debounce-render))

(defn clear-drawing-cache []
  (h/call wasm/internal-module "_clear_drawing_cache"))

(defn update-shape-tiles []
  (h/call wasm/internal-module "_update_shape_tiles"))

(defn set-object
  [objects shape]
  (perf/begin-measure "set-object")
  (let [id           (dm/get-prop shape :id)
        parent-id    (dm/get-prop shape :parent-id)
        type         (dm/get-prop shape :type)
        masked       (dm/get-prop shape :masked-group)
        selrect      (dm/get-prop shape :selrect)
        constraint-h (dm/get-prop shape :constraints-h)
        constraint-v (dm/get-prop shape :constraints-v)
        clip-content (if (= type :frame)
                       (not (dm/get-prop shape :show-content))
                       false)
        rotation     (dm/get-prop shape :rotation)
        transform    (dm/get-prop shape :transform)

        ;; Groups from imported SVG's can have their own fills
        fills        (dm/get-prop shape :fills)

        strokes      (if (= type :group)
                       [] (dm/get-prop shape :strokes))
        children     (dm/get-prop shape :shapes)
        blend-mode   (dm/get-prop shape :blend-mode)
        opacity      (dm/get-prop shape :opacity)
        hidden       (dm/get-prop shape :hidden)
        content      (dm/get-prop shape :content)
        grow-type    (dm/get-prop shape :grow-type)
        blur         (dm/get-prop shape :blur)
        corners      (when (some? (dm/get-prop shape :r1))
                       [(dm/get-prop shape :r1)
                        (dm/get-prop shape :r2)
                        (dm/get-prop shape :r3)
                        (dm/get-prop shape :r4)])
        svg-attrs    (dm/get-prop shape :svg-attrs)
        shadows      (dm/get-prop shape :shadow)]

    (use-shape id)
    (set-parent-id parent-id)
    (set-shape-type type)
    (set-shape-clip-content clip-content)
    (set-constraints-h constraint-h)
    (set-constraints-v constraint-v)
    (set-shape-rotation rotation)
    (set-shape-transform transform)
    (set-shape-blend-mode blend-mode)
    (set-shape-opacity opacity)
    (set-shape-hidden hidden)
    (set-shape-children children)
    (when (and (= type :group) masked)
      (set-masked masked))
    (when (some? blur)
      (set-shape-blur blur))
    (when (and (some? content)
               (or (= type :path)
                   (= type :bool)))
      (when (seq svg-attrs)
        (set-shape-path-attrs svg-attrs))
      (set-shape-path-content content))
    (when (and (some? content) (= type :svg-raw))
      (set-shape-svg-raw-content (get-static-markup shape)))
    (when (some? corners) (set-shape-corners corners))
    (when (some? shadows) (set-shape-shadows shadows))
    (when (= type :text)
      (set-shape-grow-type grow-type))

    (when (or (ctl/any-layout? shape)
              (ctl/any-layout-immediate-child? objects shape))
      (set-layout-child shape))

    (when (ctl/flex-layout? shape)
      (set-flex-layout shape))

    (when (ctl/grid-layout? shape)
      (set-grid-layout shape))

    (set-shape-selrect selrect)

    (let [pending (into [] (concat
                            (set-shape-text id content)
                            (set-shape-fills id fills)
                            (set-shape-strokes id strokes)))]
      (perf/end-measure "set-object")
      pending)))


(defn process-pending
  [pending]
  (let [event (js/CustomEvent. "wasm:set-objects-finished")
        pending (-> (d/index-by :key :callback pending) vals)]
    (if (not-empty? pending)
      (->> (rx/from pending)
           (rx/merge-map (fn [callback] (callback)))
           (rx/tap (fn [_] (request-render "set-objects")))
           (rx/reduce conj [])
           (rx/subs! (fn [_]
                       (.dispatchEvent ^js js/document event))))
      (.dispatchEvent ^js js/document event))))

(defn process-object
  [shape]
  (let [pending (set-object [] shape)]
    (process-pending pending)))

(defn set-objects
  [objects]
  (perf/begin-measure "set-objects")
  (let [shapes        (into [] (vals objects))
        total-shapes  (count shapes)
        pending
        (loop [index 0 pending []]
          (if (< index total-shapes)
            (let [shape    (nth shapes index)
                  pending' (set-object objects shape)]
              (recur (inc index) (into pending pending')))
            pending))]
    (perf/end-measure "set-objects")
    (clear-drawing-cache)
    (request-render "set-objects")
    (process-pending pending)))

(defn clear-focus-mode
  []
  (h/call wasm/internal-module "_clear_focus_mode")
  (clear-drawing-cache)
  (request-render "clear-focus-mode"))

(defn set-focus-mode
  [entries]
  (let [offset (mem/alloc-bytes-32 (* (count entries) 16))
        heapu32 (mem/get-heap-u32)]

    (loop [entries (seq entries)
           current-offset  offset]
      (when-not (empty? entries)
        (let [id (first entries)]
          (sr/heapu32-set-uuid id heapu32 current-offset)
          (recur (rest entries) (+ current-offset (mem/ptr8->ptr32 16))))))

    (h/call wasm/internal-module "_set_focus_mode")
    (clear-drawing-cache)
    (request-render "set-focus-mode")))

(defn set-structure-modifiers
  [entries]
  (when-not (empty? entries)
    (let [offset (mem/alloc-bytes-32 (mem/get-list-size entries 44))
          heapu32 (mem/get-heap-u32)
          heapf32 (mem/get-heap-f32)]
      (loop [entries (seq entries)
             current-offset  offset]
        (when-not (empty? entries)
          (let [{:keys [type parent id index value] :as entry} (first entries)]
            (sr/heapu32-set-u32 (sr/translate-structure-modifier-type type) heapu32 (+ current-offset 0))
            (sr/heapu32-set-u32 (or index 0) heapu32 (+ current-offset 1))
            (sr/heapu32-set-uuid parent heapu32 (+ current-offset 2))
            (sr/heapu32-set-uuid id heapu32 (+ current-offset 6))
            (aset heapf32 (+ current-offset 10) value)
            (recur (rest entries) (+ current-offset 11)))))
      (h/call wasm/internal-module "_set_structure_modifiers"))))

(defn propagate-modifiers
  [entries pixel-precision]
  (when (d/not-empty? entries)
    (let [offset (mem/alloc-bytes-32 (modifier-get-entries-size entries))
          heapf32 (mem/get-heap-f32)
          heapu32 (mem/get-heap-u32)]

      (loop [entries (seq entries)
             current-offset  offset]
        (when-not (empty? entries)
          (let [{:keys [id transform]} (first entries)]
            (sr/heapu32-set-uuid id heapu32 current-offset)
            (sr/heapf32-set-matrix transform heapf32 (+ current-offset (mem/ptr8->ptr32 MODIFIER-ENTRY-TRANSFORM-OFFSET)))
            (recur (rest entries) (+ current-offset (mem/ptr8->ptr32 MODIFIER-ENTRY-SIZE))))))

      (let [result-offset (h/call wasm/internal-module "_propagate_modifiers" pixel-precision)
            heapf32 (mem/get-heap-f32)
            heapu32 (mem/get-heap-u32)
            len (aget heapu32 (mem/ptr8->ptr32 result-offset))
            result
            (->> (range 0 len)
                 (mapv #(dr/heap32->entry heapu32 heapf32 (mem/ptr8->ptr32 (+ result-offset 4 (* % MODIFIER-ENTRY-SIZE))))))]
        (h/call wasm/internal-module "_free_bytes")

        result))))

(defn propagate-apply
  [entries pixel-precision]
  (when (d/not-empty? entries)
    (let [offset (mem/alloc-bytes-32 (modifier-get-entries-size entries))
          heapf32 (mem/get-heap-f32)
          heapu32 (mem/get-heap-u32)]

      (loop [entries (seq entries)
             current-offset  offset]
        (when-not (empty? entries)
          (let [{:keys [id transform]} (first entries)]
            (sr/heapu32-set-uuid id heapu32 current-offset)
            (sr/heapf32-set-matrix transform heapf32 (+ current-offset (mem/ptr8->ptr32 MODIFIER-ENTRY-TRANSFORM-OFFSET)))
            (recur (rest entries) (+ current-offset (mem/ptr8->ptr32 MODIFIER-ENTRY-SIZE))))))

      (let [offset (h/call wasm/internal-module "_propagate_apply" pixel-precision)
            heapf32 (mem/get-heap-f32)
            width (aget heapf32 (mem/ptr8->ptr32 (+ offset 0)))
            height (aget heapf32 (mem/ptr8->ptr32 (+ offset 4)))
            cx (aget heapf32 (mem/ptr8->ptr32 (+ offset 8)))
            cy (aget heapf32 (mem/ptr8->ptr32 (+ offset 12)))

            a (aget heapf32 (mem/ptr8->ptr32 (+ offset 16)))
            b (aget heapf32 (mem/ptr8->ptr32 (+ offset 20)))
            c (aget heapf32 (mem/ptr8->ptr32 (+ offset 24)))
            d (aget heapf32 (mem/ptr8->ptr32 (+ offset 28)))
            e (aget heapf32 (mem/ptr8->ptr32 (+ offset 32)))
            f (aget heapf32 (mem/ptr8->ptr32 (+ offset 36)))
            transform (gmt/matrix a b c d e f)]

        (h/call wasm/internal-module "_free_bytes")
        (request-render "set-modifiers")

        {:width width
         :height height
         :center (gpt/point cx cy)
         :transform transform}))))

(defn get-selection-rect
  [entries]
  (when (d/not-empty? entries)
    (let [offset (mem/alloc-bytes-32 (* (count entries) 16))
          heapu32 (mem/get-heap-u32)]

      (loop [entries (seq entries)
             current-offset  offset]
        (when-not (empty? entries)
          (let [id (first entries)]
            (sr/heapu32-set-uuid id heapu32 current-offset)
            (recur (rest entries) (+ current-offset (mem/ptr8->ptr32 16))))))

      (let [offset (h/call wasm/internal-module "_get_selection_rect")
            heapf32 (mem/get-heap-f32)
            width (aget heapf32 (mem/ptr8->ptr32 (+ offset 0)))
            height (aget heapf32 (mem/ptr8->ptr32 (+ offset 4)))
            cx (aget heapf32 (mem/ptr8->ptr32 (+ offset 8)))
            cy (aget heapf32 (mem/ptr8->ptr32 (+ offset 12)))
            a (aget heapf32 (mem/ptr8->ptr32 (+ offset 16)))
            b (aget heapf32 (mem/ptr8->ptr32 (+ offset 20)))
            c (aget heapf32 (mem/ptr8->ptr32 (+ offset 24)))
            d (aget heapf32 (mem/ptr8->ptr32 (+ offset 28)))
            e (aget heapf32 (mem/ptr8->ptr32 (+ offset 32)))
            f (aget heapf32 (mem/ptr8->ptr32 (+ offset 36)))
            transform (gmt/matrix a b c d e f)]
        (h/call wasm/internal-module "_free_bytes")
        {:width width
         :height height
         :center (gpt/point cx cy)
         :transform transform}))))

(defn set-canvas-background
  [background]
  (let [rgba (sr-clr/hex->u32argb background 1)]
    (h/call wasm/internal-module "_set_canvas_background" rgba)
    (request-render "set-canvas-background")))

(defn clean-modifiers
  []
  (h/call wasm/internal-module "_clean_modifiers"))

(defn set-modifiers
  [modifiers]
  (when-not (empty? modifiers)
    (let [offset (mem/alloc-bytes-32 (* MODIFIER-ENTRY-SIZE (count modifiers)))
          heapu32 (mem/get-heap-u32)
          heapf32 (mem/get-heap-f32)]

      (loop [entries (seq modifiers)
             current-offset  offset]
        (when-not (empty? entries)
          (let [{:keys [id transform]} (first entries)]
            (sr/heapu32-set-uuid id heapu32 current-offset)
            (sr/heapf32-set-matrix transform heapf32 (+ current-offset (mem/ptr8->ptr32 MODIFIER-ENTRY-TRANSFORM-OFFSET)))
            (recur (rest entries) (+ current-offset (mem/ptr8->ptr32 MODIFIER-ENTRY-SIZE))))))

      (h/call wasm/internal-module "_set_modifiers")

      (request-render "set-modifiers"))))

(defn initialize
  [base-objects zoom vbox background]
  (let [rgba         (sr-clr/hex->u32argb background 1)
        shapes       (into [] (vals base-objects))
        total-shapes (count shapes)]
    (h/call wasm/internal-module "_set_canvas_background" rgba)
    (h/call wasm/internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
    (h/call wasm/internal-module "_init_shapes_pool" total-shapes)
    (set-objects base-objects)))

(def ^:private canvas-options
  #js {:antialias false
       :depth true
       :stencil true
       :alpha true
       "preserveDrawingBuffer" true})

(defn resize-viewbox
  [width height]
  (h/call wasm/internal-module "_resize_viewbox" width height))

(defn- debug-flags
  []
  (cond-> 0
    (dbg/enabled? :wasm-viewbox)
    (bit-or 2r00000000000000000000000000000001)))

(defn assign-canvas
  [canvas]
  (let [gl      (unchecked-get wasm/internal-module "GL")
        flags   (debug-flags)
        context (.getContext ^js canvas "webgl2" canvas-options)
        ;; Register the context with emscripten
        handle  (.registerContext ^js gl context #js {"majorVersion" 2})]
    (.makeContextCurrent ^js gl handle)

    ;; Force the WEBGL_debug_renderer_info extension as emscripten does not enable it
    (.getExtension context "WEBGL_debug_renderer_info")

    ;; Initialize Wasm Render Engine
    (h/call wasm/internal-module "_init" (/ (.-width ^js canvas) dpr) (/ (.-height ^js canvas) dpr))
    (h/call wasm/internal-module "_set_render_options" flags dpr))
  (set! (.-width canvas) (* dpr (.-clientWidth ^js canvas)))
  (set! (.-height canvas) (* dpr (.-clientHeight ^js canvas))))

(defn clear-canvas
  []
  ;; TODO: perform corresponding cleaning
  (h/call wasm/internal-module "_clean_up"))

(defn show-grid
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call wasm/internal-module "_show_grid"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3)))
  (request-render "show-grid"))

(defn clear-grid
  []
  (h/call wasm/internal-module "_hide_grid")
  (request-render "clear-grid"))

(defn get-grid-coords
  [position]
  (let [offset  (h/call wasm/internal-module
                        "_get_grid_coords"
                        (get position :x)
                        (get position :y))
        heapi32 (mem/get-heap-i32)
        row     (aget heapi32 (mem/ptr8->ptr32 (+ offset 0)))
        column  (aget heapi32 (mem/ptr8->ptr32 (+ offset 4)))]
    (h/call wasm/internal-module "_free_bytes")
    [row column]))

(defonce module
  (delay
    (if (exists? js/dynamicImport)
      (let [uri (cf/resolve-static-asset "js/render_wasm.js")]
        (->> (js/dynamicImport (str uri))
             (p/mcat (fn [module]
                       (let [default (unchecked-get module "default")]
                         (default))))
             (p/fmap (fn [module]
                       (set! wasm/internal-module module)
                       true))
             (p/merr (fn [cause]
                       (js/console.error cause)
                       (p/resolved false)))))
      (p/resolved false))))
