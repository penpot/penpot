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
   [app.common.types.fills :as types.fills]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.types.path :as path]
   [app.common.types.path.impl :as path.impl]
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
   [app.render-wasm.mem.heap32 :as mem.h32]
   [app.render-wasm.performance :as perf]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.wasm :as wasm]
   [app.util.debug :as dbg]
   [app.util.functions :as fns]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(def use-dpr? (contains? cf/flags :render-wasm-dpr))

(def ^:const UUID-U8-SIZE 16)
(def ^:const UUID-U32-SIZE (/ UUID-U8-SIZE 4))

(def ^:const MODIFIER-U8-SIZE 40)
(def ^:const MODIFIER-U32-SIZE (/ MODIFIER-U8-SIZE 4))
(def ^:const MODIFIER-TRANSFORM-U8-OFFSET-SIZE 16)

(def ^:const GRID-LAYOUT-ROW-U8-SIZE 5)
(def ^:const GRID-LAYOUT-COLUMN-U8-SIZE 5)
(def ^:const GRID-LAYOUT-CELL-U8-SIZE 37)

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
  (set! wasm/internal-frame-id nil)
  ;; emit custom event
  (let [event (js/CustomEvent. "wasm:render")]
    (js/document.dispatchEvent ^js event)))

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
  [children]
  (perf/begin-measure "set-shape-children")
  (when-not ^boolean (empty? children)
    (let [heap   (mem/get-heap-u32)
          size   (mem/get-alloc-size children UUID-U8-SIZE)
          offset (mem/alloc->offset-32 size)]
      (reduce (fn [offset id]
                (mem.h32/write-uuid offset heap id))
              offset
              children)))

  (let [result (h/call wasm/internal-module "_set_children")]
    (perf/end-measure "set-shape-children")
    result))

(defn- get-string-length
  [string]
  (+ (count string) 1))

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
                               ;; FIXME use bigger heap ptr size if it
                               ;; is possible (if image size modulo
                               ;; permits it)
                               (let [size    (.-byteLength image)
                                     offset  (mem/alloc size)
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
    (let [id (get image :id)
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
    (let [fills  (types.fills/coerce fills)
          offset (mem/alloc->offset-32 (types.fills/get-byte-size fills))
          heap   (mem/get-heap-u32)]

      ;; write fills to the heap
      (types.fills/write-to fills heap offset)

      ;; send fills to wasm
      (h/call wasm/internal-module "_set_shape_fills")

      ;; load images for image fills if not cached
      (keep (fn [id]
              (let [buffer        (uuid/get-u32 id)
                    cached-image? (h/call wasm/internal-module "_is_image_cached"
                                          (aget buffer 0)
                                          (aget buffer 1)
                                          (aget buffer 2)
                                          (aget buffer 3))]
                (when (zero? cached-image?)
                  (fetch-image shape-id id))))

            (types.fills/get-image-ids fills)))))

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
                offset    (mem/alloc types.fills.impl/FILL-U8-SIZE)
                heap      (mem/get-heap-u8)
                dview     (js/DataView. (.-buffer heap))]
            (case align
              :inner (h/call wasm/internal-module "_add_shape_inner_stroke" width style cap-start cap-end)
              :outer (h/call wasm/internal-module "_add_shape_outer_stroke" width style cap-start cap-end)
              (h/call wasm/internal-module "_add_shape_center_stroke" width style cap-start cap-end))

            (cond
              (some? gradient)
              (do
                (types.fills.impl/write-gradient-fill offset dview opacity gradient)
                (h/call wasm/internal-module "_add_shape_stroke_fill"))

              (some? image)
              (let [image-id      (get image :id)
                    buffer        (uuid/get-u32 image-id)
                    cached-image? (h/call wasm/internal-module "_is_image_cached" (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))]
                (types.fills.impl/write-image-fill offset dview opacity image)
                (h/call wasm/internal-module "_add_shape_stroke_fill")
                (when (== cached-image? 0)
                  (fetch-image shape-id image-id)))

              (some? color)
              (do
                (types.fills.impl/write-solid-fill offset dview opacity color)
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
        offset   (mem/alloc size)]
    (h/call wasm/internal-module "stringToUTF8" str offset size)
    (h/call wasm/internal-module "_set_shape_path_attrs" (count attrs))))

;; FIXME: revisit on heap refactor is merged to use u32 instead u8
(defn set-shape-path-content
  [content]
  (let [pdata  (path/content content)
        size   (path/get-byte-size content)
        offset (mem/alloc size)
        heap   (mem/get-heap-u8)]
    (path/write-to pdata (.-buffer heap) offset)
    (h/call wasm/internal-module "_set_shape_path_content")))

(defn set-shape-svg-raw-content
  [content]
  (let [size (get-string-length content)
        offset (mem/alloc size)]
    (h/call wasm/internal-module "stringToUTF8" content offset size)
    (h/call wasm/internal-module "_set_shape_svg_raw_content")))

(defn set-shape-blend-mode
  [blend-mode]
  ;; These values correspond to skia::BlendMode representation
  ;; https://rust-skia.github.io/doc/skia_safe/enum.BlendMode.html
  (h/call wasm/internal-module "_set_shape_blend_mode" (sr/translate-blend-mode blend-mode)))

(defn set-shape-vertical-align
  [vertical-align]
  (h/call wasm/internal-module "_set_shape_vertical_align" (sr/translate-vertical-align vertical-align)))

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

(defn set-shape-constraints
  [constraint-h constraint-v]
  (h/call wasm/internal-module "_clear_shape_constraints")
  (set-constraints-h constraint-h)
  (set-constraints-v constraint-v))

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
  [shape]
  (let [r1 (get shape :r1)
        r2 (get shape :r2)
        r3 (get shape :r3)
        r4 (get shape :r4)]
    (h/call wasm/internal-module "_set_shape_corners"
            (d/nilv r1 0)
            (d/nilv r2 0)
            (d/nilv r3 0)
            (d/nilv r4 0))))

(defn set-flex-layout
  [shape]
  (let [dir        (-> (get shape :layout-flex-dir :row)
                       (sr/translate-layout-flex-dir))
        gap        (get shape :layout-gap)
        row-gap    (get gap :row-gap 0)
        column-gap (get gap :column-gap 0)

        align-items     (-> (get shape :layout-align-items) sr/translate-layout-align-items)
        align-content   (-> (get shape :layout-align-content) sr/translate-layout-align-content)
        justify-items   (-> (get shape :layout-justify-items) sr/translate-layout-justify-items)
        justify-content (-> (get shape :layout-justify-content) sr/translate-layout-justify-content)
        wrap-type       (-> (get shape :layout-wrap-type) sr/translate-layout-wrap-type)

        padding         (get shape :layout-padding)
        padding-top     (get padding :p1 0)
        padding-right   (get padding :p2 0)
        padding-bottom  (get padding :p3 0)
        padding-left    (get padding :p4 0)]

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
  (let [dir        (-> (get shape :layout-grid-dir :row)
                       (sr/translate-layout-grid-dir))
        gap        (get shape :layout-gap)
        row-gap    (get gap :row-gap 0)
        column-gap (get gap :column-gap 0)

        align-items     (-> (get shape :layout-align-items) sr/translate-layout-align-items)
        align-content   (-> (get shape :layout-align-content) sr/translate-layout-align-content)
        justify-items   (-> (get shape :layout-justify-items) sr/translate-layout-justify-items)
        justify-content (-> (get shape :layout-justify-content) sr/translate-layout-justify-content)

        padding         (get shape :layout-padding)
        padding-top     (get padding :p1 0)
        padding-right   (get padding :p2 0)
        padding-bottom  (get padding :p3 0)
        padding-left    (get padding :p4 0)]

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
  (let [size    (mem/get-alloc-size entries GRID-LAYOUT-ROW-U8-SIZE)
        offset  (mem/alloc size)
        dview   (mem/get-data-view)]

    (reduce (fn [offset {:keys [type value]}]
              ;; NOTE: because of the nature of the grid row data
              ;; structure memory layout we can't use fully 32 bits
              ;; alligned writes, so for heteregeneus writes we use
              ;; the buffer abstraction (DataView) for perform
              ;; surgical writes.
              (-> offset
                  (mem/write-u8 dview (sr/translate-grid-track-type type))
                  (mem/write-f32 dview value)
                  (mem/assert-written offset GRID-LAYOUT-ROW-U8-SIZE)))

            offset
            entries)

    (h/call wasm/internal-module "_set_grid_rows")))

(defn set-grid-layout-columns
  [entries]
  (let [size   (mem/get-alloc-size entries GRID-LAYOUT-COLUMN-U8-SIZE)
        offset (mem/alloc size)
        dview  (mem/get-data-view)]

    (reduce (fn [offset {:keys [type value]}]
              ;; NOTE: because of the nature of the grid column data
              ;; structure memory layout we can't use fully 32 bits
              ;; alligned writes, so for heteregeneus writes we use
              ;; the buffer abstraction (DataView) for perform
              ;; surgical writes.
              (-> offset
                  (mem/write-u8 dview (sr/translate-grid-track-type type))
                  (mem/write-f32 dview value)
                  (mem/assert-written offset GRID-LAYOUT-COLUMN-U8-SIZE)))


            offset
            entries)

    (h/call wasm/internal-module "_set_grid_columns")))

(defn set-grid-layout-cells
  [cells]
  (let [size    (mem/get-alloc-size cells GRID-LAYOUT-CELL-U8-SIZE)
        offset  (mem/alloc size)
        dview   (mem/get-data-view)]

    (reduce-kv (fn [offset _ cell]
                 (let [shape-id  (-> (get cell :shapes) first)]
                   (-> offset
                       ;; row: [u8; 4],
                       (mem/write-i32 dview (get cell :row))

                       ;; row_span: [u8; 4],
                       (mem/write-i32 dview (get cell :row-span))

                       ;; column: [u8; 4],
                       (mem/write-i32 dview (get cell :column))

                       ;; column_span: [u8; 4],
                       (mem/write-i32 dview (get cell :column-span))

                       ;; has_align_self: u8,
                       (mem/write-bool dview (some? (get cell :align-self)))

                       ;; align_self: u8,
                       (mem/write-u8 dview (get cell :align-self))

                       ;; has_justify_self: u8,
                       (mem/write-bool dview (get cell :justify-self))

                       ;; justify_self: u8,
                       (mem/write-u8 dview (sr/translate-justify-self (get cell :justify-self)))

                       ;; has_shape_id: u8,
                       ;; (.set heap (sr/bool->u8 (d/not-empty? (:shapes cell))) (+ current-offset 20))
                       (mem/write-u8 dview (some? shape-id))

                       ;; shape_id_a: [u8; 4],
                       ;; shape_id_b: [u8; 4],
                       ;; shape_id_c: [u8; 4],
                       ;; shape_id_d: [u8; 4],
                       (mem/write-uuid dview (d/nilv shape-id uuid/zero))
                       (mem/assert-written offset GRID-LAYOUT-CELL-U8-SIZE))))

               offset
               cells)

    (h/call wasm/internal-module "_set_grid_cells")))

(defn set-grid-layout
  [shape]
  (set-grid-layout-data shape)
  (set-grid-layout-rows (get shape :layout-grid-rows))
  (set-grid-layout-columns (get shape :layout-grid-columns))
  (set-grid-layout-cells (get shape :layout-grid-cells)))

(defn set-layout-child
  [shape]
  (let [margins       (get shape :layout-item-margin)
        margin-top    (get margins :m1 0)
        margin-right  (get margins :m2 0)
        margin-bottom (get margins :m3 0)
        margin-left   (get margins :m4 0)

        h-sizing      (-> (get shape :layout-item-h-sizing) sr/translate-layout-sizing)
        v-sizing      (-> (get shape :layout-item-v-sizing) sr/translate-layout-sizing)
        align-self    (-> (get shape :layout-item-align-self) sr/translate-align-self)

        max-h         (get shape :layout-item-max-h)
        has-max-h     (some? max-h)
        min-h         (get shape :layout-item-min-h)
        has-min-h     (some? min-h)
        max-w         (get shape :layout-item-max-w)
        has-max-w     (some? max-w)
        min-w         (get shape :layout-item-min-w)
        has-min-w     (some? min-w)
        is-absolute   (boolean (get shape :layout-item-absolute))
        z-index       (get shape :layout-item-z-index)]
    (h/call wasm/internal-module
            "_set_layout_child_data"
            margin-top
            margin-right
            margin-bottom
            margin-left
            h-sizing
            v-sizing
            has-max-h
            (d/nilv max-h 0)
            has-min-h
            (d/nilv min-h 0)
            has-max-w
            (d/nilv max-w 0)
            has-min-w
            (d/nilv min-w 0)
            (some? align-self)
            (d/nilv align-self 0)
            is-absolute
            (d/nilv z-index))))

(defn clear-layout
  []
  (h/call wasm/internal-module "_clear_shape_layout"))

(defn- set-shape-layout
  [shape objects]
  (clear-layout)

  (when (or (ctl/any-layout? shape)
            (ctl/any-layout-immediate-child? objects shape))
    (set-layout-child shape))

  (when (ctl/flex-layout? shape)
    (set-flex-layout shape))

  (when (ctl/grid-layout? shape)
    (set-grid-layout shape)))

(defn set-shape-shadows
  [shadows]
  (h/call wasm/internal-module "_clear_shape_shadows")

  (run! (fn [shadow]
          (let [color  (get shadow :color)
                blur   (get shadow :blur)
                rgba   (sr-clr/hex->u32argb (get color :color)
                                            (get color :opacity))
                hidden (get shadow :hidden)
                x      (get shadow :offset-x)
                y      (get shadow :offset-y)
                spread (get shadow :spread)
                style  (get shadow :style)]
            (h/call wasm/internal-module "_add_shape_shadow"
                    rgba
                    blur
                    spread
                    x
                    y
                    (sr/translate-shadow-style style)
                    hidden)))
        shadows))

(defn set-shape-text-content
  [shape-id content]
  (h/call wasm/internal-module "_clear_shape_text")
  (set-shape-vertical-align (get content :vertical-align))

  (let [paragraph-set (first (get content :children))
        paragraphs    (get paragraph-set :children)
        fonts         (fonts/get-content-fonts content)
        total         (count paragraphs)]

    (loop [index  0
           emoji? false
           langs  #{}]

      (if (< index total)
        (let [paragraph (nth paragraphs index)
              leaves    (get paragraph :children)]
          (if (empty? (seq leaves))
            (recur (inc index)
                   emoji?
                   langs)

            (let [text   (apply str (map :text leaves))
                  emoji? (if emoji? emoji? (t/contains-emoji? text))
                  langs  (t/collect-used-languages langs text)]

              (t/write-shape-text leaves paragraph text)
              (recur (inc index)
                     emoji?
                     langs))))

        (let [updated-fonts
              (-> fonts
                  (cond-> ^boolean emoji? (f/add-emoji-font))
                  (f/add-noto-fonts langs))]
          (f/store-fonts shape-id updated-fonts))))))

(defn set-shape-text
  [shape-id content]
  (concat
   (set-shape-text-images shape-id content)
   (set-shape-text-content shape-id content)))

(defn set-shape-grow-type
  [grow-type]
  (h/call wasm/internal-module "_set_shape_grow_type" (sr/translate-grow-type grow-type)))

(defn get-text-dimensions
  ([id]
   (use-shape id)
   (get-text-dimensions))
  ([]
   (let [offset    (-> (h/call wasm/internal-module "_get_text_dimensions")
                       (mem/->offset-32))
         heapf32   (mem/get-heap-f32)
         width     (aget heapf32 (+ offset 0))
         height    (aget heapf32 (+ offset 1))
         max-width (aget heapf32 (+ offset 2))]
     (mem/free)
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
        type         (dm/get-prop shape :type)

        parent-id    (get shape :parent-id)
        masked       (get shape :masked-group)
        selrect      (get shape :selrect)
        constraint-h (get shape :constraints-h)
        constraint-v (get shape :constraints-v)
        clip-content (if (= type :frame)
                       (not (get shape :show-content))
                       false)
        rotation     (get shape :rotation)
        transform    (get shape :transform)

        ;; Groups from imported SVG's can have their own fills
        fills        (get shape :fills)

        strokes      (if (= type :group)
                       [] (get shape :strokes))
        children     (get shape :shapes)
        blend-mode   (get shape :blend-mode)
        opacity      (get shape :opacity)
        hidden       (get shape :hidden)
        content      (get shape :content)
        bool-type    (get shape :bool-type)
        grow-type    (get shape :grow-type)
        blur         (get shape :blur)
        svg-attrs    (get shape :svg-attrs)
        shadows      (get shape :shadow)]

    (use-shape id)
    (set-parent-id parent-id)
    (set-shape-type type)
    (set-shape-clip-content clip-content)
    (set-shape-constraints constraint-h constraint-v)

    (set-shape-rotation rotation)
    (set-shape-transform transform)
    (set-shape-blend-mode blend-mode)
    (set-shape-opacity opacity)
    (set-shape-hidden hidden)
    (set-shape-children children)
    (set-shape-corners shape)
    (when (and (= type :group) masked)
      (set-masked masked))
    (when (some? blur)
      (set-shape-blur blur))
    (when (= type :bool)
      (set-shape-bool-type bool-type))
    (when (and (some? content)
               (or (= type :path)
                   (= type :bool)))
      (when (seq svg-attrs)
        (set-shape-path-attrs svg-attrs))
      (set-shape-path-content content))
    (when (and (some? content) (= type :svg-raw))
      (set-shape-svg-raw-content (get-static-markup shape)))
    (when (some? shadows) (set-shape-shadows shadows))
    (when (= type :text)
      (set-shape-grow-type grow-type))

    (set-shape-layout shape objects)

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
                       (clear-drawing-cache)
                       (request-render "pending-finished")
                       (.dispatchEvent ^js js/document event))))
      (do
        (clear-drawing-cache)
        (request-render "pending-finished")
        (.dispatchEvent ^js js/document event)))))

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
    (process-pending pending)))

(defn clear-focus-mode
  []
  (h/call wasm/internal-module "_clear_focus_mode")
  (clear-drawing-cache)
  (request-render "clear-focus-mode"))

(defn set-focus-mode
  [entries]
  (when-not ^boolean (empty? entries)
    (let [size   (mem/get-alloc-size entries UUID-U8-SIZE)
          heap   (mem/get-heap-u32)
          offset (mem/alloc->offset-32 size)]

      (reduce (fn [offset id]
                (mem.h32/write-uuid offset heap id))
              offset
              entries)

      (h/call wasm/internal-module "_set_focus_mode")
      (clear-drawing-cache)
      (request-render "set-focus-mode"))))

(defn set-structure-modifiers
  [entries]
  (when-not ^boolean (empty? entries)
    (let [size    (mem/get-alloc-size entries 44)
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

      (h/call wasm/internal-module "_set_structure_modifiers"))))

(defn propagate-modifiers
  [entries pixel-precision]
  (when-not ^boolean (empty? entries)
    (let [heapf32 (mem/get-heap-f32)
          heapu32 (mem/get-heap-u32)
          size    (mem/get-alloc-size entries MODIFIER-U8-SIZE)
          offset  (mem/alloc->offset-32 size)]

      (reduce (fn [offset [id transform]]
                (-> offset
                    (mem.h32/write-uuid heapu32 id)
                    (mem.h32/write-matrix heapf32 transform)))
              offset
              entries)

      (let [offset     (-> (h/call wasm/internal-module "_propagate_modifiers" pixel-precision)
                           (mem/->offset-32))
            length     (aget heapu32 offset)
            max-offset (+ offset 1 (* length MODIFIER-U32-SIZE))
            result     (loop [result (transient [])
                              offset (inc offset)]
                         (if (< offset max-offset)
                           (let [entry (dr/read-modifier-entry heapu32 heapf32 offset)]
                             (recur (conj! result entry)
                                    (+ offset MODIFIER-U32-SIZE)))
                           (persistent! result)))]

        (mem/free)
        result))))

(defn get-selection-rect
  [entries]

  (when-not ^boolean (empty? entries)
    (let [size    (mem/get-alloc-size entries UUID-U8-SIZE)
          offset  (mem/alloc->offset-32 size)
          heapu32 (mem/get-heap-u32)
          heapf32 (mem/get-heap-f32)]

      (reduce (fn [offset id]
                (mem.h32/write-uuid offset heapu32 id))
              offset
              entries)

      (let [offset (-> (h/call wasm/internal-module "_get_selection_rect")
                       (mem/->offset-32))
            result (dr/read-selection-rect heapf32 offset)]
        (mem/free)
        result))))

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

  ;; We need to ensure efficient operations
  (assert (vector? modifiers) "expected a vector for `set-modifiers`")

  (let [length (count modifiers)]
    (when (pos? length)
      (let [offset  (mem/alloc->offset-32 (* MODIFIER-U8-SIZE length))
            heapu32 (mem/get-heap-u32)
            heapf32 (mem/get-heap-f32)]

        (reduce (fn [offset [id transform]]
                  (-> offset
                      (mem.h32/write-uuid heapu32 id)
                      (mem.h32/write-matrix heapf32 transform)))
                offset
                modifiers)

        (h/call wasm/internal-module "_set_modifiers")

        (request-render "set-modifiers")))))

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
        row     (aget heapi32 (mem/->offset-32 (+ offset 0)))
        column  (aget heapi32 (mem/->offset-32 (+ offset 4)))]
    (mem/free)
    [row column]))

(defn shape-to-path
  [id]
  (use-shape id)
  (let [offset (-> (h/call wasm/internal-module "_current_to_path")
                   (mem/->offset-32))
        heap   (mem/get-heap-u32)
        length (aget heap offset)
        data   (mem/slice heap
                          (+ offset 1)
                          (* length path.impl/SEGMENT-U32-SIZE))
        content (path/from-bytes data)]
    (mem/free)
    content))

(defn- calculate-bool*
  [bool-type]
  (-> (h/call wasm/internal-module "_calculate_bool" (sr/translate-bool-type bool-type))
      (mem/->offset-32)))

(defn calculate-bool
  [bool-type ids]

  (let [size   (mem/get-alloc-size ids UUID-U8-SIZE)
        heap   (mem/get-heap-u32)
        offset (mem/alloc->offset-32 size)]

    (reduce (fn [offset id]
              (mem.h32/write-uuid offset heap id))
            offset
            (rseq ids))

    (let [offset  (calculate-bool* bool-type)
          length  (aget heap offset)
          data    (mem/slice heap
                             (+ offset 1)
                             (* length path.impl/SEGMENT-U32-SIZE))
          content (path/from-bytes data)]
      (mem/free)
      content)))

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
