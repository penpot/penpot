;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api
  "A WASM based render API"
  (:require
   ["react-dom/server" :as rds]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as log]
   [app.common.math :as mth]
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
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.render-wasm.api.fonts :as f]
   [app.render-wasm.api.texts :as t]
   [app.render-wasm.deserializers :as dr]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.mem.heap32 :as mem.h32]
   [app.render-wasm.performance :as perf]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.svg-fills :as svg-fills]
   ;; FIXME: rename; confunsing name
   [app.render-wasm.wasm :as wasm]
   [app.util.debug :as dbg]
   [app.util.functions :as fns]
   [app.util.globals :as ug]
   [app.util.text.content :as tc]
   [beicon.v2.core :as rx]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(def use-dpr? (contains? cf/flags :render-wasm-dpr))

(def ^:const UUID-U8-SIZE 16)
(def ^:const UUID-U32-SIZE (/ UUID-U8-SIZE 4))

(def ^:const MODIFIER-U8-SIZE 40)
(def ^:const MODIFIER-U32-SIZE (/ MODIFIER-U8-SIZE 4))
(def ^:const MODIFIER-TRANSFORM-U8-OFFSET-SIZE 16)

(def ^:const GRID-LAYOUT-ROW-U8-SIZE 8)
(def ^:const GRID-LAYOUT-COLUMN-U8-SIZE 8)
(def ^:const GRID-LAYOUT-CELL-U8-SIZE 36)

(def ^:const MAX_BUFFER_CHUNK_SIZE (* 256 1024))

(def dpr
  (if use-dpr? (if (exists? js/window) js/window.devicePixelRatio 1.0) 1.0))

(def noop-fn
  (constantly nil))

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
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_render" timestamp)
    (set! wasm/internal-frame-id nil)
    (ug/dispatch! (ug/event "penpot:wasm:render"))))

(defn render-sync
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_render_sync")
    (set! wasm/internal-frame-id nil)))

(defn render-sync-shape
  [id]
  (when wasm/context-initialized?
    (let [buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_render_sync_shape"
              (aget buffer 0)
              (aget buffer 1)
              (aget buffer 2)
              (aget buffer 3))
      (set! wasm/internal-frame-id nil))))


(defonce pending-render (atom false))

(defn request-render
  [_requester]
  (when (not @pending-render)
    (reset! pending-render true)
    (js/requestAnimationFrame
     (fn [ts]
       (reset! pending-render false)
       (render ts)))))

(declare get-text-dimensions)

(defn update-text-rect!
  [id]
  (when wasm/context-initialized?
    (mw/emit!
     {:cmd :index/update-text-rect
      :page-id (:current-page-id @st/state)
      :shape-id id
      :dimensions (get-text-dimensions id)})))


(defn- ensure-text-content
  "Guarantee that the shape always sends a valid text tree to WASM. When the
  content is nil (freshly created text) we fall back to
  tc/default-text-content so the renderer receives typography information."
  [content]
  (or content (tc/v2-default-text-content)))

(defn use-shape
  [id]
  (when wasm/context-initialized?
    (let [buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_use_shape"
              (aget buffer 0)
              (aget buffer 1)
              (aget buffer 2)
              (aget buffer 3)))))

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
  (let [children (into [] (filter uuid?) children)]
    (case (count children)
      0
      (h/call wasm/internal-module "_set_children_0")

      1
      (let [[c1] children
            c1 (uuid/get-u32 c1)]
        (h/call wasm/internal-module "_set_children_1"
                (aget c1 0) (aget c1 1) (aget c1 2) (aget c1 3)))

      2
      (let [[c1 c2] children
            c1 (uuid/get-u32 c1)
            c2 (uuid/get-u32 c2)]
        (h/call wasm/internal-module "_set_children_2"
                (aget c1 0) (aget c1 1) (aget c1 2) (aget c1 3)
                (aget c2 0) (aget c2 1) (aget c2 2) (aget c2 3)))

      3
      (let [[c1 c2 c3] children
            c1 (uuid/get-u32 c1)
            c2 (uuid/get-u32 c2)
            c3 (uuid/get-u32 c3)]
        (h/call wasm/internal-module "_set_children_3"
                (aget c1 0) (aget c1 1) (aget c1 2) (aget c1 3)
                (aget c2 0) (aget c2 1) (aget c2 2) (aget c2 3)
                (aget c3 0) (aget c3 1) (aget c3 2) (aget c3 3)))

      4
      (let [[c1 c2 c3 c4] children
            c1 (uuid/get-u32 c1)
            c2 (uuid/get-u32 c2)
            c3 (uuid/get-u32 c3)
            c4 (uuid/get-u32 c4)]
        (h/call wasm/internal-module "_set_children_4"
                (aget c1 0) (aget c1 1) (aget c1 2) (aget c1 3)
                (aget c2 0) (aget c2 1) (aget c2 2) (aget c2 3)
                (aget c3 0) (aget c3 1) (aget c3 2) (aget c3 3)
                (aget c4 0) (aget c4 1) (aget c4 2) (aget c4 3)))

      5
      (let [[c1 c2 c3 c4 c5] children
            c1 (uuid/get-u32 c1)
            c2 (uuid/get-u32 c2)
            c3 (uuid/get-u32 c3)
            c4 (uuid/get-u32 c4)
            c5 (uuid/get-u32 c5)]
        (h/call wasm/internal-module "_set_children_5"
                (aget c1 0) (aget c1 1) (aget c1 2) (aget c1 3)
                (aget c2 0) (aget c2 1) (aget c2 2) (aget c2 3)
                (aget c3 0) (aget c3 1) (aget c3 2) (aget c3 3)
                (aget c4 0) (aget c4 1) (aget c4 2) (aget c4 3)
                (aget c5 0) (aget c5 1) (aget c5 2) (aget c5 3)))

      ;; Dynamic call for children > 5
      (let [heap   (mem/get-heap-u32)
            size   (mem/get-alloc-size children UUID-U8-SIZE)
            offset (mem/alloc->offset-32 size)]
        (reduce
         (fn [offset id]
           (mem.h32/write-uuid offset heap id))
         offset
         children)
        (h/call wasm/internal-module "_set_children"))))
  (perf/end-measure "set-shape-children")
  nil)

(defn- get-string-length
  [string]
  (+ (count string) 1))

(defn- create-webgl-texture-from-image
  "Creates a WebGL texture from an HTMLImageElement or ImageBitmap and returns the texture object"
  [gl image-element]
  (let [texture (.createTexture ^js gl)]
    (.bindTexture ^js gl (.-TEXTURE_2D ^js gl) texture)
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_WRAP_S ^js gl) (.-CLAMP_TO_EDGE ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_WRAP_T ^js gl) (.-CLAMP_TO_EDGE ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_MIN_FILTER ^js gl) (.-LINEAR ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_MAG_FILTER ^js gl) (.-LINEAR ^js gl))
    (.texImage2D ^js gl (.-TEXTURE_2D ^js gl) 0 (.-RGBA ^js gl) (.-RGBA ^js gl) (.-UNSIGNED_BYTE ^js gl) image-element)
    (.bindTexture ^js gl (.-TEXTURE_2D ^js gl) nil)
    texture))

(defn- get-webgl-context
  "Gets the WebGL context from the WASM module"
  []
  (when wasm/context-initialized?
    (let [gl-obj (unchecked-get wasm/internal-module "GL")]
      (when gl-obj
        ;; Get the current WebGL context from Emscripten
        ;; The GL object has a currentContext property that contains the context handle
        (let [current-ctx (.-currentContext ^js gl-obj)]
          (when current-ctx
            (.-GLctx ^js current-ctx)))))))

(defn- get-texture-id-for-gl-object
  "Registers a WebGL texture with Emscripten's GL object system and returns its ID"
  [texture]
  (let [gl-obj (unchecked-get wasm/internal-module "GL")
        textures (.-textures ^js gl-obj)
        new-id (.getNewId ^js gl-obj textures)]
    (aset textures new-id texture)
    new-id))

(defn- retrieve-image
  [url]
  (rx/from
   (-> (js/fetch url)
       (p/then (fn [^js response] (.blob response)))
       (p/then (fn [^js image] (js/createImageBitmap image))))))

(defn- fetch-image
  "Loads an image and creates a WebGL texture from it, passing the texture ID to WASM.
   This avoids decoding the image twice (once in browser, once in WASM)."
  [shape-id image-id thumbnail?]
  (let [url (cf/resolve-file-media {:id image-id} thumbnail?)]
    {:key url
     :thumbnail? thumbnail?
     :callback
     (fn []
       (->> (retrieve-image url)
            (rx/map
             (fn [img]
               (when-let [gl (get-webgl-context)]
                 (let [texture (create-webgl-texture-from-image gl img)
                       texture-id (get-texture-id-for-gl-object texture)
                       width  (.-width ^js img)
                       height (.-height ^js img)
                       ;; Header: 32 bytes (2 UUIDs) + 4 bytes (thumbnail)
                       ;;     + 4 bytes (texture ID) + 8 bytes (dimensions)
                       total-bytes 48
                       offset (mem/alloc->offset-32 total-bytes)
                       heap32 (mem/get-heap-u32)]

                   ;; 1. Set shape id (offset + 0 to offset + 3)
                   (mem.h32/write-uuid offset heap32 shape-id)

                   ;; 2. Set image id (offset + 4 to offset + 7)
                   (mem.h32/write-uuid (+ offset 4) heap32 image-id)

                   ;; 3. Set thumbnail flag as u32 (offset + 8)
                   (aset heap32 (+ offset 8) (if thumbnail? 1 0))

                   ;; 4. Set texture ID (offset + 9)
                   (aset heap32 (+ offset 9) texture-id)

                   ;; 5. Set width (offset + 10)
                   (aset heap32 (+ offset 10) width)

                   ;; 6. Set height (offset + 11)
                   (aset heap32 (+ offset 11) height)

                   (h/call wasm/internal-module "_store_image_from_texture")
                   true))))
            (rx/catch
             (fn [cause]
               (log/error :hint "Could not fetch image"
                          :image-id image-id
                          :thumbnail? thumbnail?
                          :url url
                          :cause cause)
               (rx/empty)))))}))

(defn- get-fill-images
  [leaf]
  (filter :fill-image (:fills leaf)))

(defn- process-fill-image
  [shape-id fill thumbnail?]
  (when-let [image (:fill-image fill)]
    (let [id (get image :id)
          buffer (uuid/get-u32 id)
          cached-image? (h/call wasm/internal-module "_is_image_cached"
                                (aget buffer 0)
                                (aget buffer 1)
                                (aget buffer 2)
                                (aget buffer 3)
                                thumbnail?)]
      (when (zero? cached-image?)
        (fetch-image shape-id id thumbnail?)))))

(defn set-shape-text-images
  ([shape-id content]
   (set-shape-text-images shape-id content false))
  ([shape-id content thumbnail?]
   (let [paragraph-set (first (get content :children))
         paragraphs (get paragraph-set :children)]
     (->> paragraphs
          (mapcat :children)
          (mapcat get-fill-images)
          (map #(process-fill-image shape-id % thumbnail?))))))

(defn set-shape-fills
  [shape-id fills thumbnail?]
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
                                          (aget buffer 3)
                                          thumbnail?)]
                (when (zero? cached-image?)
                  (fetch-image shape-id id thumbnail?))))

            (types.fills/get-image-ids fills)))))

(defn set-shape-strokes
  [shape-id strokes thumbnail?]
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
                    cached-image? (h/call wasm/internal-module "_is_image_cached"
                                          (aget buffer 0) (aget buffer 1)
                                          (aget buffer 2) (aget buffer 3)
                                          thumbnail?)]
                (types.fills.impl/write-image-fill offset dview opacity image)
                (h/call wasm/internal-module "_add_shape_stroke_fill")
                (when (== cached-image? 0)
                  (fetch-image shape-id image-id thumbnail?)))

              (some? color)
              (do
                (types.fills.impl/write-solid-fill offset dview opacity color)
                (h/call wasm/internal-module "_add_shape_stroke_fill")))))
        strokes))

(defn set-shape-svg-attrs
  [attrs]
  (let [style (:style attrs)
        ;; Filter to only supported attributes
        allowed-keys #{:fill :fillRule :fill-rule :strokeLinecap :stroke-linecap :strokeLinejoin :stroke-linejoin}
        attrs (-> attrs
                  (dissoc :style)
                  (merge style)
                  (select-keys allowed-keys))
        fill-rule       (-> (or (:fill-rule attrs) (:fillRule attrs)) sr/translate-fill-rule)
        stroke-linecap  (-> (or (:stroke-linecap attrs) (:strokeLinecap attrs)) sr/translate-stroke-linecap)
        stroke-linejoin (-> (or (:stroke-linejoin attrs) (:strokeLinejoin attrs)) sr/translate-stroke-linejoin)
        fill-none       (= "none" (-> attrs :fill))]
    (h/call wasm/internal-module "_set_shape_svg_attrs" fill-rule stroke-linecap stroke-linejoin fill-none)))

(defn set-shape-path-content
  "Upload path content in chunks to WASM."
  [content]
  (let [chunk-size (quot MAX_BUFFER_CHUNK_SIZE 4)
        buffer-size (path/get-byte-size content)
        padded-size (* 4 (mth/ceil (/ buffer-size 4)))
        buffer (js/Uint8Array. padded-size)]
    (path/write-to content (.-buffer buffer) 0)
    (h/call wasm/internal-module "_start_shape_path_buffer")
    (let [heapu32 (mem/get-heap-u32)]
      (loop [offset 0]
        (when (< offset padded-size)
          (let [end (min padded-size (+ offset (* chunk-size 4)))
                chunk (.subarray buffer offset end)
                chunk-u32 (js/Uint32Array. chunk.buffer chunk.byteOffset (quot (.-length chunk) 4))
                offset-size (.-length chunk-u32)
                heap-offset (mem/alloc->offset-32 (* 4 offset-size))]
            (.set heapu32 chunk-u32 heap-offset)
            (h/call wasm/internal-module "_set_shape_path_chunk_buffer")
            (recur end)))))
    (h/call wasm/internal-module "_set_shape_path_buffer")))

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

(defn set-shape-blur
  [blur]
  (if (some? blur)
    (let [type   (-> blur :type sr/translate-blur-type)
          hidden (:hidden blur)
          value  (:value blur)]
      (h/call wasm/internal-module "_set_shape_blur" type hidden value))
    (h/call wasm/internal-module "_clear_shape_blur")))

(defn set-shape-corners
  [corners]
  (let [[r1 r2 r3 r4] (map #(d/nilv % 0) corners)]
    (h/call wasm/internal-module "_set_shape_corners" r1 r2 r3 r4)))

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
              (-> offset
                  (mem/write-u8 dview (sr/translate-grid-track-type type))
                  (+ 3) ;; padding
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
              (-> offset
                  (mem/write-u8 dview (sr/translate-grid-track-type type))
                  (+ 3) ;; padding
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
                       (mem/write-i32 dview (get cell :row))
                       (mem/write-i32 dview (get cell :row-span))
                       (mem/write-i32 dview (get cell :column))
                       (mem/write-i32 dview (get cell :column-span))

                       (mem/write-u8 dview (sr/translate-align-self (get cell :align-self)))
                       (mem/write-u8 dview (sr/translate-justify-self (get cell :justify-self)))

                       ;; padding
                       (+ 2)

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

            (d/nilv align-self 0)
            is-absolute
            (d/nilv z-index 0))))

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

(defn fonts-from-text-content [content fallback-fonts-only?]
  (let [paragraph-set (first (get content :children))
        paragraphs    (get paragraph-set :children)
        total         (count paragraphs)]
    (loop [index  0
           emoji? false
           langs  #{}]

      (if (< index total)
        (let [paragraph (nth paragraphs index)
              spans    (get paragraph :children)]
          (if (empty? (seq spans))
            (recur (inc index)
                   emoji?
                   langs)

            (let [text   (apply str (map :text spans))
                  emoji? (if emoji? emoji? (t/contains-emoji? text))
                  langs  (t/collect-used-languages langs text)]

              ;; FIXME: this should probably be somewhere else
              (when fallback-fonts-only? (t/write-shape-text spans paragraph text))

              (recur (inc index)
                     emoji?
                     langs))))

        (let [updated-fonts
              (-> #{}
                  (cond-> ^boolean emoji? (f/add-emoji-font))
                  (f/add-noto-fonts langs))
              fallback-fonts (filter #(get % :is-fallback) updated-fonts)]

          (if fallback-fonts-only? updated-fonts fallback-fonts))))))

(defn set-shape-text-content
  "This function sets shape text content and returns a stream that loads the needed fonts asynchronously"
  [shape-id content]

  (h/call wasm/internal-module "_clear_shape_text")

  (set-shape-vertical-align (get content :vertical-align))

  (let [fonts         (fonts/get-content-fonts content)
        fallback-fonts (fonts-from-text-content content true)
        all-fonts (concat fonts fallback-fonts)
        result (f/store-fonts shape-id all-fonts)]
    (f/load-fallback-fonts-for-editor! fallback-fonts)
    (h/call wasm/internal-module "_update_shape_text_layout")
    result))

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
         max-width (aget heapf32 (+ offset 2))

         x (aget heapf32 (+ offset 3))
         y (aget heapf32 (+ offset 4))]
     (mem/free)
     {:x x :y y :width width :height height :max-width max-width})))

(defn intersect-position-in-shape
  [id position]
  (let [buffer (uuid/get-u32 id)
        result
        (h/call wasm/internal-module "_intersect_position_in_shape"
                (aget buffer 0)
                (aget buffer 1)
                (aget buffer 2)
                (aget buffer 3)
                (:x position)
                (:y position))]
    (= result 1)))

(def render-finish
  (letfn [(do-render [ts]
            (h/call wasm/internal-module "_set_view_end")
            (render ts))]
    (fns/debounce do-render 100)))

(def render-pan
  (fns/throttle render 10))

(defn set-view-box
  [prev-zoom zoom vbox]
  (h/call wasm/internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))

  (if (mth/close? prev-zoom zoom)
    (do (render-pan)
        (render-finish))
    (do (h/call wasm/internal-module "_render_from_cache" 0)
        (render-finish))))

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

        ;; If the shape comes from an imported SVG (we know this because
        ;; it has the :svg-attrs attribute) and it does not have its
        ;; own fill, we set a default black fill. This fill will be
        ;; inherited by child nodes and emulates the behavior of
        ;; standard SVG, where a node without an explicit fill
        ;; defaults to black.
        fills        (svg-fills/resolve-shape-fills shape)

        strokes      (if (= type :group)
                       [] (get shape :strokes))
        children     (get shape :shapes)
        blend-mode   (get shape :blend-mode)
        opacity      (get shape :opacity)
        hidden       (get shape :hidden)
        content      (let [content (get shape :content)]
                       (if (= type :text)
                         (ensure-text-content content)
                         content))
        bool-type    (get shape :bool-type)
        grow-type    (get shape :grow-type)
        blur         (get shape :blur)
        svg-attrs    (get shape :svg-attrs)
        shadows      (get shape :shadow)
        corners      (map #(get shape %) [:r1 :r2 :r3 :r4])]

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
    (set-shape-corners corners)
    (set-shape-blur blur)
    (when (and (= type :group) masked)
      (set-masked masked))
    (when (= type :bool)
      (set-shape-bool-type bool-type))
    (when (and (some? content)
               (or (= type :path)
                   (= type :bool)))
      (set-shape-path-content content))
    (when (some? svg-attrs)
      (set-shape-svg-attrs svg-attrs))
    (when (and (some? content) (= type :svg-raw))
      (set-shape-svg-raw-content (get-static-markup shape)))
    (when (some? shadows) (set-shape-shadows shadows))
    (when (= type :text)
      (set-shape-grow-type grow-type))

    (set-shape-layout shape objects)

    (set-shape-selrect selrect)

    (let [pending_thumbnails (into [] (concat
                                       (set-shape-text-content id content)
                                       (set-shape-text-images id content true)
                                       (set-shape-fills id fills true)
                                       (set-shape-strokes id strokes true)))
          pending_full (into [] (concat
                                 (set-shape-text-images id content false)
                                 (set-shape-fills id fills false)
                                 (set-shape-strokes id strokes false)))]
      (perf/end-measure "set-object")
      {:thumbnails pending_thumbnails
       :full pending_full})))

(defn update-text-layouts
  [shapes]
  (->> shapes
       (filter cfh/text-shape?)
       (map :id)
       (run!
        (fn [id]
          (f/update-text-layout id)
          (mw/emit! {:cmd :index/update-text-rect
                     :page-id (:current-page-id @st/state)
                     :shape-id id
                     :dimensions (get-text-dimensions id)})))))

(defn process-pending
  ([shapes thumbnails full on-complete]
   (process-pending shapes thumbnails full nil on-complete))
  ([shapes thumbnails full on-render on-complete]
   (let [pending-thumbnails
         (d/index-by :key :callback thumbnails)

         pending-full
         (d/index-by :key :callback full)]

     (->> (rx/concat
           (->> (rx/from (vals pending-thumbnails))
                (rx/merge-map (fn [callback] (callback)))
                (rx/reduce conj []))
           (->> (rx/from (vals pending-full))
                (rx/mapcat (fn [callback] (callback)))
                (rx/reduce conj [])))
          (rx/subs!
           (fn [_]
             (update-text-layouts shapes)
             (if on-render
               (on-render)
               (request-render "pending-finished")))
           noop-fn
           on-complete)))))

(defn process-object
  [shape]
  (let [{:keys [thumbnails full]} (set-object [] shape)]
    (process-pending [shape] thumbnails full noop-fn)))

(defn set-objects
  ([objects]
   (set-objects objects nil))
  ([objects render-callback]
   (perf/begin-measure "set-objects")
   (let [shapes        (into [] (vals objects))
         total-shapes  (count shapes)
         ;; Collect pending operations - set-object returns {:thumbnails [...] :full [...]}
         {:keys [thumbnails full]}
         (loop [index 0 thumbnails-acc [] full-acc []]
           (if (< index total-shapes)
             (let [shape    (nth shapes index)
                   {:keys [thumbnails full]} (set-object objects shape)]
               (recur (inc index)
                      (into thumbnails-acc thumbnails)
                      (into full-acc full)))
             {:thumbnails thumbnails-acc :full full-acc}))]
     (perf/end-measure "set-objects")
     (process-pending shapes thumbnails full noop-fn
                      (fn []
                        (when render-callback (render-callback))
                        (ug/dispatch! (ug/event "penpot:wasm:set-objects")))))))

(defn clear-focus-mode
  []
  (h/call wasm/internal-module "_clear_focus_mode")
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

(defn initialize-viewport
  ([base-objects zoom vbox background]
   (initialize-viewport base-objects zoom vbox background nil))
  ([base-objects zoom vbox background callback]
   (let [rgba         (sr-clr/hex->u32argb background 1)
         shapes       (into [] (vals base-objects))
         total-shapes (count shapes)]
     (h/call wasm/internal-module "_set_canvas_background" rgba)
     (h/call wasm/internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
     (h/call wasm/internal-module "_init_shapes_pool" total-shapes)
     (set-objects base-objects callback))))

(def ^:private default-context-options
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

(defn set-canvas-size
  [canvas]
  (let [width (or (.-clientWidth ^js canvas) (.-width ^js canvas))
        height (or (.-clientHeight ^js canvas) (.-height ^js canvas))]
    (set! (.-width canvas) (* dpr width))
    (set! (.-height canvas) (* dpr height))))

(defn- get-browser
  []
  (when (exists? js/navigator)
    (let [user-agent (.-userAgent js/navigator)]
      (when user-agent
        (cond
          (re-find #"(?i)firefox" user-agent) :firefox
          (re-find #"(?i)chrome" user-agent) :chrome
          (re-find #"(?i)safari" user-agent) :safari
          (re-find #"(?i)edge" user-agent) :edge
          :else :unknown)))))

(defn init-canvas-context
  [canvas]
  (let [gl      (unchecked-get wasm/internal-module "GL")
        flags   (debug-flags)
        context-id (if (dbg/enabled? :wasm-gl-context-init-error) "fail" "webgl2")
        context (.getContext ^js canvas context-id default-context-options)
        context-init? (not (nil? context))
        browser (get-browser)
        browser (sr/translate-browser browser)]
    (when-not (nil? context)
      (let [handle (.registerContext ^js gl context #js {"majorVersion" 2})]
        (.makeContextCurrent ^js gl handle)

        ;; Force the WEBGL_debug_renderer_info extension as emscripten does not enable it
        (.getExtension context "WEBGL_debug_renderer_info")

        ;; Initialize Wasm Render Engine
        (h/call wasm/internal-module "_init" (/ (.-width ^js canvas) dpr) (/ (.-height ^js canvas) dpr))
        (h/call wasm/internal-module "_set_render_options" flags dpr))
      (set! wasm/context-initialized? true))

    (h/call wasm/internal-module "_set_browser" browser)

    (h/call wasm/internal-module "_set_render_options" flags dpr)
    (set-canvas-size canvas)
    context-init?))

(defn clear-canvas
  []
  ;; TODO: perform corresponding cleaning
  (set! wasm/context-initialized? false)
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

(defn init-wasm-module
  [module]
  (let [default-fn (unchecked-get module "default")
        href       (cf/resolve-href "js/render-wasm.wasm")]
    (default-fn #js {:locateFile (constantly href)})))

(defonce module
  (delay
    (if (exists? js/dynamicImport)
      (let [uri (cf/resolve-href "js/render-wasm.js")]
        (->> (js/dynamicImport (str uri))
             (p/mcat init-wasm-module)
             (p/fmap
              (fn [default]
                (set! wasm/internal-module default)
                true))
             (p/merr
              (fn [cause]
                (js/console.error cause)
                (p/resolved false)))))
      (p/resolved false))))
