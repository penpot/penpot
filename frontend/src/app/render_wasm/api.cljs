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
   [app.common.geom.matrix :as gmt]
   [app.common.math :as mth]
   [app.common.svg.path :as path]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.render :as render]
   [app.main.store :as st]
   [app.main.ui.shapes.text.fontfaces :as fonts]
   [app.render-wasm.helpers :as h]
   [app.util.debug :as dbg]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [lambdaisland.uri :as u]
   [okulary.core :as l]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(defonce internal-frame-id nil)
(defonce internal-module #js {})
(defonce use-dpr? (contains? cf/flags :render-wasm-dpr))

(def dpr
  (if use-dpr? js/window.devicePixelRatio 1.0))

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
;; This function receives a "time" parameter that we're not using but maybe in the future could be useful (it is the time since
;; the window started rendering elements so it could be useful to measure time between frames).
(defn- render
  [_]
  (h/call internal-module "_render")
  (set! internal-frame-id nil))

(defn- rgba-from-hex
  "Takes a hex color in #rrggbb format, and an opacity value from 0 to 1 and returns its 32-bit rgba representation"
  [hex opacity]
  (let [rgb (js/parseInt (subs hex 1) 16)
        a (mth/floor (* (or opacity 1) 0xff))]
        ;; rgba >>> 0 so we have an unsigned representation
    (unsigned-bit-shift-right (bit-or (bit-shift-left a 24) rgb) 0)))

(defn- rgba-bytes-from-hex
  "Takes a hex color in #rrggbb format, and an opacity value from 0 to 1 and returns an array with its r g b a values"
  [hex opacity]
  (let [rgb (js/parseInt (subs hex 1) 16)
        a (mth/floor (* (or opacity 1) 0xff))
        ;; rgba >>> 0 so we have an unsigned representation
        r (bit-shift-right rgb 16)
        g (bit-and (bit-shift-right rgb 8) 255)
        b (bit-and rgb 255)]
    [r g b a]))

(defn cancel-render
  [_]
  (when internal-frame-id
    (js/cancelAnimationFrame internal-frame-id)
    (set! internal-frame-id nil)))

(defn request-render
  [requester]
  (when internal-frame-id (cancel-render requester))
  (let [frame-id (js/requestAnimationFrame render)]
    (set! internal-frame-id frame-id)))

(defn use-shape
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call internal-module "_use_shape"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-parent-id
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call internal-module "_set_parent"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-shape-clip-content
  [clip-content]
  (h/call internal-module "_set_shape_clip_content" clip-content))

(defn- translate-shape-type
  [type]
  (case type
    :frame   0
    :group   1
    :bool    2
    :rect    3
    :path    4
    :text    5
    :circle  6
    :svg-raw 7
    :image   8))

(defn set-shape-type
  [type]
  (h/call internal-module "_set_shape_type" (translate-shape-type type)))

(defn set-masked
  [masked]
  (h/call internal-module "_set_shape_masked_group" masked))

(defn set-shape-selrect
  [selrect]
  (h/call internal-module "_set_shape_selrect"
          (dm/get-prop selrect :x1)
          (dm/get-prop selrect :y1)
          (dm/get-prop selrect :x2)
          (dm/get-prop selrect :y2)))

(defn set-shape-transform
  [transform]
  (h/call internal-module "_set_shape_transform"
          (dm/get-prop transform :a)
          (dm/get-prop transform :b)
          (dm/get-prop transform :c)
          (dm/get-prop transform :d)
          (dm/get-prop transform :e)
          (dm/get-prop transform :f)))

(defn set-shape-rotation
  [rotation]
  (h/call internal-module "_set_shape_rotation" rotation))

(defn set-shape-children
  [shape-ids]
  (h/call internal-module "_clear_shape_children")
  (run! (fn [id]
          (let [buffer (uuid/get-u32 id)]
            (h/call internal-module "_add_shape_child"
                    (aget buffer 0)
                    (aget buffer 1)
                    (aget buffer 2)
                    (aget buffer 3))))
        shape-ids))

(defn- get-string-length [string] (+ (count string) 1))

;; IMPORTANT: It should be noted that only TTF fonts can be stored.
(defn- store-font-buffer
  [font-data font-array-buffer]
  (let [id-buffer (:family-id-buffer font-data)
        size (.-byteLength font-array-buffer)
        ptr  (h/call internal-module "_alloc_bytes" size)
        heap (gobj/get ^js internal-module "HEAPU8")
        mem  (js/Uint8Array. (.-buffer heap) ptr size)]
    (.set mem (js/Uint8Array. font-array-buffer))
    (h/call internal-module "_store_font"
            (aget id-buffer 0)
            (aget id-buffer 1)
            (aget id-buffer 2)
            (aget id-buffer 3)
            (:weight font-data)
            (:style font-data))
    true))

(defn- store-font-url
  [font-data font-url]
  (->> (http/send! {:method :get
                    :uri font-url
                    :response-type :blob})
       (rx/map :body)
       (rx/mapcat wapi/read-file-as-array-buffer)
       (rx/map (fn [array-buffer] (store-font-buffer font-data array-buffer)))))

(defn- store-font-id
  [font-data asset-id]
  (when asset-id
    (let [uri (str (u/join cf/public-uri "assets/by-id/" asset-id))
          id-buffer (uuid/get-u32 (:family-id font-data))
          font-data (assoc font-data :family-id-buffer id-buffer)
          font-stored? (not= 0 (h/call internal-module "_is_font_uploaded"
                                       (aget id-buffer 0)
                                       (aget id-buffer 1)
                                       (aget id-buffer 2)
                                       (aget id-buffer 3)
                                       (:weight font-data)
                                       (:style font-data)))]
      (when-not font-stored? (store-font-url font-data uri)))))

(defn- store-image
  [id]

  (let [buffer (uuid/get-u32 id)
        url    (cf/resolve-file-media {:id id})]
    (->> (http/send! {:method :get
                      :uri url
                      :response-type :blob})
         (rx/map :body)
         (rx/mapcat wapi/read-file-as-array-buffer)
         (rx/map (fn [image]
                   (let [image-size (.-byteLength image)
                         image-ptr  (h/call internal-module "_alloc_bytes" image-size)
                         heap       (gobj/get ^js internal-module "HEAPU8")
                         mem        (js/Uint8Array. (.-buffer heap) image-ptr image-size)]
                     (.set mem (js/Uint8Array. image))
                     (h/call internal-module "_store_image"
                             (aget buffer 0)
                             (aget buffer 1)
                             (aget buffer 2)
                             (aget buffer 3))
                     true))))))

(defn set-shape-fills
  [fills]
  (h/call internal-module "_clear_shape_fills")
  (keep (fn [fill]
          (let [opacity  (or (:fill-opacity fill) 1.0)
                color    (:fill-color fill)
                gradient (:fill-color-gradient fill)
                image    (:fill-image fill)]

            (cond
              (some? color)
              (let [rgba (rgba-from-hex color opacity)]
                (h/call internal-module "_add_shape_solid_fill" rgba))

              (some? gradient)
              (let [stops     (:stops gradient)
                    n-stops   (count stops)
                    mem-size  (* 5 n-stops)
                    stops-ptr (h/call internal-module "_alloc_bytes" mem-size)
                    heap      (gobj/get ^js internal-module "HEAPU8")
                    mem       (js/Uint8Array. (.-buffer heap) stops-ptr mem-size)]
                (if (= (:type gradient) :linear)
                  (h/call internal-module "_add_shape_linear_fill"
                          (:start-x gradient)
                          (:start-y gradient)
                          (:end-x gradient)
                          (:end-y gradient)
                          opacity)
                  (h/call internal-module "_add_shape_radial_fill"
                          (:start-x gradient)
                          (:start-y gradient)
                          (:end-x gradient)
                          (:end-y gradient)
                          opacity
                          (:width gradient)))
                (.set mem (js/Uint8Array. (clj->js (flatten (map (fn [stop]
                                                                   (let [[r g b a] (rgba-bytes-from-hex (:color stop) (:opacity stop))
                                                                         offset (:offset stop)]
                                                                     [r g b a (* 100 offset)]))
                                                                 stops)))))
                (h/call internal-module "_add_shape_fill_stops"))

              (some? image)
              (let [id            (dm/get-prop image :id)
                    buffer        (uuid/get-u32 id)
                    cached-image? (h/call internal-module "_is_image_cached" (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))]
                (h/call internal-module "_add_shape_image_fill"
                        (aget buffer 0)
                        (aget buffer 1)
                        (aget buffer 2)
                        (aget buffer 3)
                        opacity
                        (dm/get-prop image :width)
                        (dm/get-prop image :height))
                (when (== cached-image? 0)
                  (store-image id))))))
        fills))

(defn- translate-stroke-style
  [stroke-style]
  (case stroke-style
    :dotted 1
    :dashed 2
    :mixed  3
    0))

(defn- translate-stroke-cap
  [stroke-cap]
  (case stroke-cap
    :line-arrow 1
    :triangle-arrow 2
    :square-marker 3
    :circle-marker 4
    :diamond-marker 5
    :round 6
    :square 7
    0))

(defn set-shape-strokes
  [strokes]
  (h/call internal-module "_clear_shape_strokes")
  (keep (fn [stroke]
          (let [opacity   (or (:stroke-opacity stroke) 1.0)
                color     (:stroke-color stroke)
                gradient  (:stroke-color-gradient stroke)
                image     (:stroke-image stroke)
                width     (:stroke-width stroke)
                align     (:stroke-alignment stroke)
                style     (-> stroke :stroke-style translate-stroke-style)
                cap-start (-> stroke :stroke-cap-start translate-stroke-cap)
                cap-end   (-> stroke :stroke-cap-end translate-stroke-cap)]
            (case align
              :inner (h/call internal-module "_add_shape_inner_stroke" width style cap-start cap-end)
              :outer (h/call internal-module "_add_shape_outer_stroke" width style cap-start cap-end)
              (h/call internal-module "_add_shape_center_stroke" width style cap-start cap-end))

            (cond
              (some? gradient)
              (let [stops     (:stops gradient)
                    n-stops   (count stops)
                    mem-size  (* 5 n-stops)
                    stops-ptr (h/call internal-module "_alloc_bytes" mem-size)
                    heap      (gobj/get ^js internal-module "HEAPU8")
                    mem       (js/Uint8Array. (.-buffer heap) stops-ptr mem-size)]
                (if (= (:type gradient) :linear)
                  (h/call internal-module "_add_shape_stroke_linear_fill"
                          (:start-x gradient)
                          (:start-y gradient)
                          (:end-x gradient)
                          (:end-y gradient)
                          opacity)
                  (h/call internal-module "_add_shape_stroke_radial_fill"
                          (:start-x gradient)
                          (:start-y gradient)
                          (:end-x gradient)
                          (:end-y gradient)
                          opacity
                          (:width gradient)))
                (.set mem (js/Uint8Array. (clj->js (flatten (map (fn [stop]
                                                                   (let [[r g b a] (rgba-bytes-from-hex (:color stop) (:opacity stop))
                                                                         offset (:offset stop)]
                                                                     [r g b a (* 100 offset)]))
                                                                 stops)))))
                (h/call internal-module "_add_shape_stroke_stops"))

              (some? image)
              (let [id            (dm/get-prop image :id)
                    buffer        (uuid/get-u32 id)
                    cached-image? (h/call internal-module "_is_image_cached" (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))]
                (h/call internal-module "_add_shape_image_stroke"
                        (aget buffer 0)
                        (aget buffer 1)
                        (aget buffer 2)
                        (aget buffer 3)
                        opacity
                        (dm/get-prop image :width)
                        (dm/get-prop image :height))
                (when (== cached-image? 0)
                  (store-image id)))

              (some? color)
              (let [rgba (rgba-from-hex color opacity)]
                (h/call internal-module "_add_shape_stroke_solid_fill" rgba)))))
        strokes))

(defn serialize-path-attrs
  [svg-attrs]
  (reduce
   (fn [acc [key value]]
     (str/concat
      acc
      (str/kebab key) "\0"
      value "\0")) "" svg-attrs))

(defn set-shape-path-attrs
  [attrs]
  (let [style (:style attrs)
        attrs (-> attrs
                  (dissoc :style)
                  (merge style))
        str   (serialize-path-attrs attrs)
        size  (count str)
        ptr   (h/call internal-module "_alloc_bytes" size)]
    (h/call internal-module "stringToUTF8" str ptr size)
    (h/call internal-module "_set_shape_path_attrs" (count attrs))))

(defn set-shape-path-content
  [content]
  (let [buffer    (path/content->buffer content)
        size      (.-byteLength buffer)
        ptr       (h/call internal-module "_alloc_bytes" size)
        heap      (gobj/get ^js internal-module "HEAPU8")
        mem       (js/Uint8Array. (.-buffer heap) ptr size)]
    (.set mem (js/Uint8Array. buffer))
    (h/call internal-module "_set_shape_path_content")))

(defn set-shape-svg-raw-content
  [content]
  (let [size (get-string-length content)
        ptr (h/call internal-module "_alloc_bytes" size)]
    (h/call internal-module "stringToUTF8" content ptr size)
    (h/call internal-module "_set_shape_svg_raw_content")))

(defn- translate-blend-mode
  [blend-mode]
  (case blend-mode
    :normal 3
    :darken 16
    :multiply 24
    :color-burn 19
    :lighten 17
    :screen 14
    :color-dodge 18
    :overlay 15
    :soft-light 21
    :hard-light 20
    :difference 22
    :exclusion 23
    :hue 25
    :saturation 26
    :color 27
    :luminosity 28
    3))

(defn set-shape-blend-mode
  [blend-mode]
  ;; These values correspond to skia::BlendMode representation
  ;; https://rust-skia.github.io/doc/skia_safe/enum.BlendMode.html
  (h/call internal-module "_set_shape_blend_mode" (translate-blend-mode blend-mode)))

(defn set-shape-opacity
  [opacity]
  (h/call internal-module "_set_shape_opacity" (or opacity 1)))

(defn- translate-constraint-h
  [type]
  (case type
    :left      0
    :right     1
    :leftright 2
    :center    3
    :scale     4))

(defn set-constraints-h
  [constraint]
  (when constraint
    (h/call internal-module "_set_shape_constraint_h" (translate-constraint-h constraint))))

(defn- translate-constraint-v
  [type]
  (case type
    :top       0
    :bottom    1
    :topbottom 2
    :center    3
    :scale     4))

(defn set-constraints-v
  [constraint]
  (when constraint
    (h/call internal-module "_set_shape_constraint_v" (translate-constraint-v constraint))))

(defn set-shape-hidden
  [hidden]
  (h/call internal-module "_set_shape_hidden" hidden))

(defn- translate-bool-type
  [bool-type]
  (case bool-type
    :union 0
    :difference 1
    :intersection 2
    :exclusion 3
    0))

(defn set-shape-bool-type
  [bool-type]
  (h/call internal-module "_set_shape_bool_type" (translate-bool-type bool-type)))

(defn set-shape-bool-content
  [content]
  (set-shape-path-content content))

(defn- translate-blur-type
  [blur-type]
  (case blur-type
    :layer-blur 1
    0))

(defn set-shape-blur
  [blur]
  (let [type   (-> blur :type translate-blur-type)
        hidden (:hidden blur)
        value  (:value blur)]
    (h/call internal-module "_set_shape_blur" type hidden value)))

(defn set-shape-corners
  [corners]
  (let [r1 (or (get corners 0) 0)
        r2 (or (get corners 1) 0)
        r3 (or (get corners 2) 0)
        r4 (or (get corners 3) 0)]
    (h/call internal-module "_set_shape_corners" r1 r2 r3 r4)))


(defn translate-layout-flex-dir
  [flex-dir]
  (case flex-dir
    :row            0
    :row-reverse    1
    :column         2
    :column-reverse 3))

(defn translate-layout-align-items
  [align-items]
  (case align-items
    :start   0
    :end     1
    :center  2
    :stretch 3))

(defn translate-layout-align-content
  [align-content]
  (case align-content
    :start         0
    :end           1
    :center        2
    :space-between 3
    :space-around  4
    :space-evenly  5
    :stretch       6))

(defn translate-layout-justify-items
  [justify-items]
  (case justify-items
    :start   0
    :end     1
    :center  2
    :stretch 3))

(defn translate-layout-justify-content
  [justify-content]
  (case justify-content
    :start         0
    :end           1
    :center        2
    :space-between 3
    :space-around  4
    :space-evenly  5
    :stretch       6))

(defn translate-layout-wrap-type
  [wrap-type]
  (case wrap-type
    :wrap   0
    :nowrap 1))

(defn set-flex-layout
  [shape]
  (let [dir (-> (or (dm/get-prop shape :layout-flex-dir) :row) translate-layout-flex-dir)
        gap (dm/get-prop shape :layout-gap)
        row-gap (or (dm/get-prop gap :row-gap) 0)
        column-gap (or (dm/get-prop gap :column-gap) 0)

        align-items (-> (or (dm/get-prop shape :layout-align-items) :start) translate-layout-align-items)
        align-content (-> (or (dm/get-prop shape :layout-align-content) :stretch) translate-layout-align-content)
        justify-items (-> (or (dm/get-prop shape :layout-justify-items) :start) translate-layout-justify-items)
        justify-content (-> (or (dm/get-prop shape :layout-justify-content) :stretch) translate-layout-justify-content)
        wrap-type (-> (or (dm/get-prop shape :layout-wrap-type) :nowrap) translate-layout-wrap-type)

        padding (dm/get-prop shape :layout-padding)
        padding-top (or (dm/get-prop padding :p1) 0)
        padding-right (or (dm/get-prop padding :p2) 0)
        padding-bottom (or (dm/get-prop padding :p3) 0)
        padding-left (or (dm/get-prop padding :p4) 0)]
    (h/call internal-module
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

(defn set-grid-layout
  [_shape])

(defn translate-layout-sizing
  [value]
  (case value
    :fill 0
    :fix  1
    :auto 2))

(defn translate-align-self
  [value]
  (when value
    (case value
      :start   0
      :end     1
      :center  2
      :stretch 3)))

(defn set-layout-child
  [shape]
  (let [margins (dm/get-prop shape :layout-item-margin)
        margin-top (or (dm/get-prop margins :m1) 0)
        margin-right (or (dm/get-prop margins :m2) 0)
        margin-bottom (or (dm/get-prop margins :m3) 0)
        margin-left (or (dm/get-prop margins :m4) 0)

        h-sizing (-> (dm/get-prop shape :layout-item-h-sizing) (or :auto) translate-layout-sizing)
        v-sizing (-> (dm/get-prop shape :layout-item-v-sizing) (or :auto) translate-layout-sizing)
        align-self (-> (dm/get-prop shape :layout-item-align-self) translate-align-self)

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
    (h/call internal-module
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

(defn- translate-shadow-style
  [style]
  (case style
    :drop-shadow 0
    :inner-shadow 1
    0))

(defn set-shape-shadows
  [shadows]
  (h/call internal-module "_clear_shape_shadows")
  (let [total-shadows (count shadows)]
    (loop [index 0]
      (when (< index total-shadows)
        (let [shadow (nth shadows index)
              color (dm/get-prop shadow :color)
              blur (dm/get-prop shadow :blur)
              rgba (rgba-from-hex (dm/get-prop color :color) (dm/get-prop color :opacity))
              hidden (dm/get-prop shadow :hidden)
              x (dm/get-prop shadow :offset-x)
              y (dm/get-prop shadow :offset-y)
              spread (dm/get-prop shadow :spread)
              style (dm/get-prop shadow :style)]
          (h/call internal-module "_add_shape_shadow" rgba blur spread x y (translate-shadow-style style) hidden)
          (recur (inc index)))))))

(defn utf8->buffer [text]
  (let [encoder (js/TextEncoder.)]
    (.encode encoder text)))

(def ^:private fonts
  (l/derived :fonts st/state))

(defn ^:private font->ttf-id [font-uuid font-style font-weight]
  (let [matching-font (d/seek (fn [[_ font]]
                                (and (= (:font-id font) font-uuid)
                                     (= (:font-style font) font-style)
                                     (= (:font-weight font) font-weight)))
                              (seq @fonts))]
    (when matching-font
      (:ttf-file-id (second matching-font)))))

(defn- serialize-font-style
  [font-style]
  (case font-style
    "normal" 0
    "regular" 0
    "italic" 1
    0))

(defn- serialize-font-id
  [font-id]
  (let [no-prefix (subs font-id (inc (str/index-of font-id "-")))
        as-uuid (uuid/uuid no-prefix)]
    (uuid/get-u32 as-uuid)))

(defn- serialize-font-weight
  [font-weight]
  (js/Number font-weight))

(defn- add-text-leaf [leaf]
  (let [text (dm/get-prop leaf :text)
        font-id (serialize-font-id (dm/get-prop leaf :font-id))
        font-style (serialize-font-style (dm/get-prop leaf :font-style))
        font-weight (serialize-font-weight (dm/get-prop leaf :font-weight))
        font-size (js/Number (dm/get-prop leaf :font-size))
        buffer (utf8->buffer text)
        size (.-byteLength buffer)
        ptr (h/call internal-module "_alloc_bytes" size)
        heap (gobj/get ^js internal-module "HEAPU8")
        mem (js/Uint8Array. (.-buffer heap) ptr size)]
    (.set mem buffer)
    (h/call internal-module "_add_text_leaf"
            (aget font-id 0)
            (aget font-id 1)
            (aget font-id 2)
            (aget font-id 3)
            font-weight font-style font-size)))

(defn set-shape-text-content [content]
  (h/call internal-module "_clear_shape_text")
  (let [paragraph-set (first (dm/get-prop content :children))
        paragraphs (dm/get-prop paragraph-set :children)
        total-paragraphs (count paragraphs)]

    (loop [index 0]
      (when (< index total-paragraphs)
        (let [paragraph (nth paragraphs index)
              leaves (dm/get-prop paragraph :children)
              total-leaves (count leaves)]
          (h/call internal-module "_add_text_paragraph")
          (loop [index-leaves 0]
            (when (< index-leaves total-leaves)
              (let [leaf (nth leaves index-leaves)]
                (add-text-leaf leaf)
                (recur (inc index-leaves))))))
        (recur (inc index))))))

(defn set-view-box
  [zoom vbox]
  (h/call internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
  (render nil))

(defn clear-cache []
  (h/call internal-module "_clear_cache"))

(defn- store-all-fonts
  [fonts]
  (keep (fn [font]
          (let [font-id (dm/get-prop font :font-id)
                font-variant (dm/get-prop font :font-variant-id)
                variant-parts (str/split font-variant #"\-")
                style (first variant-parts)
                weight (serialize-font-weight (last variant-parts))
                font-id (subs font-id (inc (str/index-of font-id "-")))
                font-id (uuid/uuid font-id)
                ttf-id (font->ttf-id font-id style weight)
                font-data {:family-id font-id
                           :style (serialize-font-style style)
                           :weight weight}]
            (store-font-id font-data ttf-id))) fonts))

(defn set-fonts
  [objects]
  (let [fonts (fonts/shapes->fonts (into [] (vals objects)))
        pending (into [] (store-all-fonts fonts))]
    (->> (rx/from pending)
         (rx/mapcat identity)
         (rx/reduce conj [])
         (rx/subs! request-render))))

(defn set-objects
  [objects]
  (set-fonts objects)
  (let [shapes        (into [] (vals objects))
        total-shapes  (count shapes)
        pending
        (loop [index 0 pending []]
          (if (< index total-shapes)
            (let [shape        (nth shapes index)
                  id           (dm/get-prop shape :id)
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
                  fills        (if (= type :group)
                                 [] (dm/get-prop shape :fills))
                  strokes      (if (= type :group)
                                 [] (dm/get-prop shape :strokes))
                  children     (dm/get-prop shape :shapes)
                  blend-mode   (dm/get-prop shape :blend-mode)
                  opacity      (dm/get-prop shape :opacity)
                  hidden       (dm/get-prop shape :hidden)
                  content      (dm/get-prop shape :content)
                  blur         (dm/get-prop shape :blur)
                  corners      (when (some? (dm/get-prop shape :r1))
                                 [(dm/get-prop shape :r1)
                                  (dm/get-prop shape :r2)
                                  (dm/get-prop shape :r3)
                                  (dm/get-prop shape :r4)])
                  bool-content (dm/get-prop shape :bool-content)
                  svg-attrs    (dm/get-prop shape :svg-attrs)
                  shadows      (dm/get-prop shape :shadow)]

              (use-shape id)
              (set-parent-id parent-id)
              (set-shape-type type)
              (set-shape-clip-content clip-content)
              (set-shape-selrect selrect)
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
              (when (and (some? content) (= type :path))
                (set-shape-path-attrs svg-attrs)
                (set-shape-path-content content))
              (when (and (some? content) (= type :svg-raw))
                (set-shape-svg-raw-content (get-static-markup shape)))
              (when (some? bool-content) (set-shape-bool-content bool-content))
              (when (some? corners) (set-shape-corners corners))
              (when (some? shadows) (set-shape-shadows shadows))
              (when (and (= type :text) (some? content))
                (set-shape-text-content content))

              (when (or (ctl/any-layout? shape)
                        (ctl/any-layout-immediate-child? objects shape))
                (set-layout-child shape))

              (when (ctl/flex-layout? shape)
                (set-flex-layout shape))

              (when (ctl/grid-layout? shape)
                (set-grid-layout shape))

              (let [pending' (concat (set-shape-fills fills) (set-shape-strokes strokes))]
                (recur (inc index) (into pending pending'))))
            pending))]
    (clear-cache)
    (request-render "set-objects")
    (when-let [pending (seq pending)]
      (->> (rx/from pending)
           (rx/mapcat identity)
           (rx/reduce conj [])
           (rx/subs! request-render)))))

(defn uuid->u8
  [id]
  (let [buffer (uuid/get-u32 id)
        u32-arr (js/Uint32Array. 4)]
    (doseq [i (range 0 4)]
      (aset u32-arr i (aget buffer i)))
    (js/Uint8Array. (.-buffer u32-arr))))

(defn matrix->u8
  [{:keys [a b c d e f]}]
  (let [f32-arr (js/Float32Array. 6)]
    (aset f32-arr 0 a)
    (aset f32-arr 1 b)
    (aset f32-arr 2 c)
    (aset f32-arr 3 d)
    (aset f32-arr 4 e)
    (aset f32-arr 5 f)
    (js/Uint8Array. (.-buffer f32-arr))))

(defn data->entry
  [data offset]
  (let [id1 (.getUint32 data (+ offset 0) true)
        id2 (.getUint32 data (+ offset 4) true)
        id3 (.getUint32 data (+ offset 8) true)
        id4 (.getUint32 data (+ offset 12) true)

        a (.getFloat32 data (+ offset 16) true)
        b (.getFloat32 data (+ offset 20) true)
        c (.getFloat32 data (+ offset 24) true)
        d (.getFloat32 data (+ offset 28) true)
        e (.getFloat32 data (+ offset 32) true)
        f (.getFloat32 data (+ offset 36) true)

        id (uuid/from-unsigned-parts id1 id2 id3 id4)]

    {:id id
     :transform (gmt/matrix a b c d e f)}))

(defn propagate-modifiers
  [entries]
  (let [entry-size 40
        ptr (h/call internal-module "_alloc_bytes" (* entry-size (count entries)))

        heap
        (js/Uint8Array.
         (.-buffer (gobj/get ^js internal-module "HEAPU8"))
         ptr
         (* entry-size (count entries)))]

    (loop [entries (seq entries)
           offset  0]
      (when-not (empty? entries)
        (let [{:keys [id transform]} (first entries)]
          (.set heap (uuid->u8 id) offset)
          (.set heap (matrix->u8 transform) (+ offset 16))
          (recur (rest entries) (+ offset entry-size)))))

    (let [result-ptr (h/call internal-module "_propagate_modifiers")
          heap (js/DataView. (.-buffer (gobj/get ^js internal-module "HEAPU8")))
          len (.getUint32 heap result-ptr true)
          result
          (->> (range 0 len)
               (mapv #(data->entry heap (+ result-ptr 4 (* % entry-size)))))]
      (h/call internal-module "_free_bytes")

      result)))

(defn set-canvas-background
  [background]
  (let [rgba (rgba-from-hex background 1)]
    (h/call internal-module "_set_canvas_background" rgba)
    (request-render "set-canvas-background")))

(defn set-modifiers
  [modifiers]
  (if (empty? modifiers)
    (h/call internal-module "_clean_modifiers")

    (let [ENTRY_SIZE 40

          ptr
          (h/call internal-module "_alloc_bytes" (* ENTRY_SIZE (count modifiers)))

          heap
          (js/Uint8Array.
           (.-buffer (gobj/get ^js internal-module "HEAPU8"))
           ptr
           (* ENTRY_SIZE (count modifiers)))]

      (loop [entries (seq modifiers)
             offset  0]
        (when-not (empty? entries)
          (let [{:keys [id transform]} (first entries)]
            (.set heap (uuid->u8 id) offset)
            (.set heap (matrix->u8 transform) (+ offset 16))
            (recur (rest entries) (+ offset ENTRY_SIZE)))))

      (h/call internal-module "_set_modifiers")

      (request-render "set-modifiers"))))

(defn initialize
  [base-objects zoom vbox background]
  (let [rgba (rgba-from-hex background 1)]
    (h/call internal-module "_set_canvas_background" rgba)
    (h/call internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
    (set-objects base-objects)))

(def ^:private canvas-options
  #js {:antialias false
       :depth true
       :stencil true
       :alpha true})

(defn resize-viewbox
  [width height]
  (h/call internal-module "_resize_viewbox" width height))

(defn- debug-flags
  []
  (cond-> 0
    (dbg/enabled? :wasm-viewbox)
    (bit-or 2r00000000000000000000000000000001)))

(defn assign-canvas
  [canvas]
  (let [gl      (unchecked-get internal-module "GL")
        flags   (debug-flags)
        context (.getContext ^js canvas "webgl2" canvas-options)
        ;; Register the context with emscripten
        handle  (.registerContext ^js gl context #js {"majorVersion" 2})]
    (.makeContextCurrent ^js gl handle)

    ;; Force the WEBGL_debug_renderer_info extension as emscripten does not enable it
    (.getExtension context "WEBGL_debug_renderer_info")

    ;; Initialize Wasm Render Engine
    (h/call internal-module "_init" (/ (.-width ^js canvas) dpr) (/ (.-height ^js canvas) dpr))
    (h/call internal-module "_set_render_options" flags dpr))
  (set! (.-width canvas) (* dpr (.-clientWidth ^js canvas)))
  (set! (.-height canvas) (* dpr (.-clientHeight ^js canvas))))

(defn clear-canvas
  []
  ;; TODO: perform corresponding cleaning
  (h/call internal-module "_clean_up"))

(defonce module
  (delay
    (if (exists? js/dynamicImport)
      (let [uri (cf/resolve-static-asset "js/render_wasm.js")]
        (->> (js/dynamicImport (str uri))
             (p/mcat (fn [module]
                       (let [default (unchecked-get module "default")]
                         (default))))
             (p/fmap (fn [module]
                       (set! internal-module module)
                       true))
             (p/merr (fn [cause]
                       (js/console.error cause)
                       (p/resolved false)))))
      (p/resolved false))))
