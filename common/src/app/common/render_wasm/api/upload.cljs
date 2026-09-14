;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.api.upload
  "Enlarged per-shape + multi-shape structural upload for WASM cold load.

  Writes a binary batch consumed by `_set_shapes_batch`. Remaining
  host-specific attrs (image bytes, text, path, grid tracks) are applied
  afterwards via the existing per-shape setters."
  (:require
   [app.common.buffer :as buf]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.serializers.color :as sr-clr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.fills :as types.fills]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

(def ^:const BASE-PROPS-SIZE 104)
(def ^:const FLAG-CLIP-CONTENT 0x01)
(def ^:const FLAG-HIDDEN 0x02)

(def ^:const SECTION-CHILDREN 0x01)
(def ^:const SECTION-BLUR-LAYER 0x02)
(def ^:const SECTION-BLUR-BG 0x04)
(def ^:const SECTION-SHADOWS 0x08)
(def ^:const SECTION-MASKED 0x10)
(def ^:const SECTION-BOOL-TYPE 0x20)
(def ^:const SECTION-GROW-TYPE 0x40)
(def ^:const SECTION-LAYOUT-ITEM 0x80)
(def ^:const SECTION-FLEX 0x100)
(def ^:const SECTION-FILLS 0x200)
(def ^:const SECTION-STROKES 0x400)

;; Stroke header before RawFillData (must match upload_batch.rs).
(def ^:const STROKE-HEADER-U8-SIZE 36)
(def ^:const STROKE-ALIGN-CENTER 0)
(def ^:const STROKE-ALIGN-INNER 1)
(def ^:const STROKE-ALIGN-OUTER 2)

(defn- write-uuid!
  [dview offset id]
  (buf/write-uuid dview offset id)
  (+ offset 16))

(defn- write-base-props!
  "Write the 104-byte RawBasePropsData at `offset`. Returns next offset."
  [dview offset shape]
  (let [id           (dm/get-prop shape :id)
        parent-id    (get shape :parent-id)
        shape-type   (dm/get-prop shape :type)
        clip-content (if (= shape-type :frame)
                       (not (get shape :show-content))
                       false)
        hidden       (get shape :hidden false)
        flags        (cond-> 0
                       clip-content (bit-or FLAG-CLIP-CONTENT)
                       hidden       (bit-or FLAG-HIDDEN))
        blend-mode   (sr/translate-blend-mode (get shape :blend-mode))
        constraint-h (sr/translate-constraint-h (or (get shape :constraints-h) :none))
        constraint-v (sr/translate-constraint-v (or (get shape :constraints-v) :none))
        opacity      (d/nilv (get shape :opacity) 1.0)
        rotation     (d/nilv (get shape :rotation) 0.0)
        transform    (get shape :transform)
        [ta tb tc td te tf]
        (if (some? transform)
          [(dm/get-prop transform :a)
           (dm/get-prop transform :b)
           (dm/get-prop transform :c)
           (dm/get-prop transform :d)
           (dm/get-prop transform :e)
           (dm/get-prop transform :f)]
          [1.0 0.0 0.0 1.0 0.0 0.0])
        selrect (get shape :selrect)
        [sx1 sy1 sx2 sy2]
        (if (some? selrect)
          [(dm/get-prop selrect :x1)
           (dm/get-prop selrect :y1)
           (dm/get-prop selrect :x2)
           (dm/get-prop selrect :y2)]
          [0.0 0.0 0.0 0.0])
        r1 (d/nilv (get shape :r1) 0.0)
        r2 (d/nilv (get shape :r2) 0.0)
        r3 (d/nilv (get shape :r3) 0.0)
        r4 (d/nilv (get shape :r4) 0.0)]

    (write-uuid! dview offset id)
    (write-uuid! dview (+ offset 16) (d/nilv parent-id uuid/zero))
    (buf/write-u8 dview (+ offset 32) (sr/translate-shape-type shape-type))
    (buf/write-u8 dview (+ offset 33) flags)
    (buf/write-u8 dview (+ offset 34) blend-mode)
    (buf/write-u8 dview (+ offset 35) constraint-h)
    (buf/write-u8 dview (+ offset 36) constraint-v)
    (buf/write-f32 dview (+ offset 40) opacity)
    (buf/write-f32 dview (+ offset 44) rotation)
    (buf/write-f32 dview (+ offset 48) ta)
    (buf/write-f32 dview (+ offset 52) tb)
    (buf/write-f32 dview (+ offset 56) tc)
    (buf/write-f32 dview (+ offset 60) td)
    (buf/write-f32 dview (+ offset 64) te)
    (buf/write-f32 dview (+ offset 68) tf)
    (buf/write-f32 dview (+ offset 72) sx1)
    (buf/write-f32 dview (+ offset 76) sy1)
    (buf/write-f32 dview (+ offset 80) sx2)
    (buf/write-f32 dview (+ offset 84) sy2)
    (buf/write-f32 dview (+ offset 88) r1)
    (buf/write-f32 dview (+ offset 92) r2)
    (buf/write-f32 dview (+ offset 96) r3)
    (buf/write-f32 dview (+ offset 100) r4)
    (+ offset BASE-PROPS-SIZE)))

(defn- write-blur!
  [dview offset blur]
  (buf/write-u8 dview offset (if (get blur :hidden) 1 0))
  (buf/write-f32 dview (+ offset 4) (get blur :value 0))
  (+ offset 8))

(defn- write-shadow!
  [dview offset shadow]
  (let [color  (get shadow :color)
        rgba   (sr-clr/hex->u32argb (get color :color)
                                    (get color :opacity))]
    (buf/write-u32 dview offset rgba)
    (buf/write-f32 dview (+ offset 4) (get shadow :blur 0))
    (buf/write-f32 dview (+ offset 8) (get shadow :spread 0))
    (buf/write-f32 dview (+ offset 12) (get shadow :offset-x 0))
    (buf/write-f32 dview (+ offset 16) (get shadow :offset-y 0))
    (buf/write-u8 dview (+ offset 20) (sr/translate-shadow-style (get shadow :style)))
    (buf/write-u8 dview (+ offset 21) (if (get shadow :hidden) 1 0))
    (+ offset 24)))

(defn- write-flex!
  [dview offset shape]
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
    (buf/write-u8 dview offset dir)
    (buf/write-u8 dview (+ offset 1) align-items)
    (buf/write-u8 dview (+ offset 2) align-content)
    (buf/write-u8 dview (+ offset 3) justify-items)
    (buf/write-u8 dview (+ offset 4) justify-content)
    (buf/write-u8 dview (+ offset 5) wrap-type)
    (buf/write-f32 dview (+ offset 8) row-gap)
    (buf/write-f32 dview (+ offset 12) column-gap)
    (buf/write-f32 dview (+ offset 16) padding-top)
    (buf/write-f32 dview (+ offset 20) padding-right)
    (buf/write-f32 dview (+ offset 24) padding-bottom)
    (buf/write-f32 dview (+ offset 28) padding-left)
    (+ offset 32)))

(defn- write-layout-item!
  [dview offset shape]
  (let [margins       (get shape :layout-item-margin)
        margin-top    (get margins :m1 0)
        margin-right  (get margins :m2 0)
        margin-bottom (get margins :m3 0)
        margin-left   (get margins :m4 0)
        h-sizing      (-> (get shape :layout-item-h-sizing) sr/translate-layout-sizing)
        v-sizing      (-> (get shape :layout-item-v-sizing) sr/translate-layout-sizing)
        align-self    (-> (get shape :layout-item-align-self) sr/translate-align-self)
        max-h         (get shape :layout-item-max-h)
        min-h         (get shape :layout-item-min-h)
        max-w         (get shape :layout-item-max-w)
        min-w         (get shape :layout-item-min-w)
        is-absolute   (boolean (get shape :layout-item-absolute))
        z-index       (get shape :layout-item-z-index)
        flags         (cond-> 0
                        (some? max-h) (bit-or 0x01)
                        (some? min-h) (bit-or 0x02)
                        (some? max-w) (bit-or 0x04)
                        (some? min-w) (bit-or 0x08)
                        is-absolute   (bit-or 0x10))]
    (buf/write-f32 dview offset margin-top)
    (buf/write-f32 dview (+ offset 4) margin-right)
    (buf/write-f32 dview (+ offset 8) margin-bottom)
    (buf/write-f32 dview (+ offset 12) margin-left)
    (buf/write-u8 dview (+ offset 16) (d/nilv h-sizing 0))
    (buf/write-u8 dview (+ offset 17) (d/nilv v-sizing 0))
    (buf/write-u8 dview (+ offset 18) flags)
    (buf/write-u8 dview (+ offset 19) (d/nilv align-self 0))
    (buf/write-f32 dview (+ offset 20) (d/nilv max-h 0))
    (buf/write-f32 dview (+ offset 24) (d/nilv min-h 0))
    (buf/write-f32 dview (+ offset 28) (d/nilv max-w 0))
    (buf/write-f32 dview (+ offset 32) (d/nilv min-w 0))
    (buf/write-i32 dview (+ offset 36) (d/nilv z-index 0))
    (+ offset 40)))

(defn- write-fills-section!
  "Write fills in the same layout as `_set_shape_fills`:
  [u8 n][u8;3 pad][n × FILL-U8-SIZE]. Returns next offset."
  [dview offset fills]
  (let [fills     (types.fills/coerce (or fills []))
        byte-size (types.fills/get-byte-size fills)
        ;; write-to expects a Uint32Array heap + u32 element offset
        heap-u32  (js/Uint32Array. (.-buffer dview))
        u32-off   (quot offset 4)]
    (types.fills/write-to fills heap-u32 u32-off)
    (+ offset byte-size)))

(defn- write-stroke-fill!
  [dview offset stroke]
  (let [opacity  (or (:stroke-opacity stroke) 1.0)
        color    (:stroke-color stroke)
        gradient (:stroke-color-gradient stroke)
        image    (:stroke-image stroke)]
    (cond
      (some? gradient)
      (types.fills.impl/write-gradient-fill offset dview opacity gradient)

      (some? image)
      (types.fills.impl/write-image-fill offset dview opacity image)

      (some? color)
      (types.fills.impl/write-solid-fill offset dview opacity color)

      :else
      (types.fills.impl/write-solid-fill offset dview 0.0 "#000000"))))

(defn- write-stroke!
  [dview offset stroke]
  (let [width     (or (:stroke-width stroke) 1.0)
        style     (-> stroke :stroke-style sr/translate-stroke-style)
        align     (case (:stroke-alignment stroke)
                    :inner STROKE-ALIGN-INNER
                    :outer STROKE-ALIGN-OUTER
                    STROKE-ALIGN-CENTER)
        cap-start (-> stroke :stroke-cap-start sr/translate-stroke-cap)
        cap-end   (-> stroke :stroke-cap-end sr/translate-stroke-cap)
        dash      (or (:stroke-dash stroke) -1)
        gap       (or (:stroke-gap stroke) -1)
        per-side? (boolean (:stroke-per-side stroke))
        top       (or (:stroke-width-top stroke) width)
        right     (or (:stroke-width-right stroke) width)
        bottom    (or (:stroke-width-bottom stroke) width)
        left      (or (:stroke-width-left stroke) width)
        has-sides? (and per-side? (not= top right bottom left))]
    (buf/write-f32 dview offset width)
    (buf/write-u8 dview (+ offset 4) style)
    (buf/write-u8 dview (+ offset 5) align)
    (buf/write-u8 dview (+ offset 6) (d/nilv cap-start 0))
    (buf/write-u8 dview (+ offset 7) (d/nilv cap-end 0))
    (buf/write-f32 dview (+ offset 8) dash)
    (buf/write-f32 dview (+ offset 12) gap)
    (buf/write-u8 dview (+ offset 16) (if has-sides? 1 0))
    (buf/write-f32 dview (+ offset 20) top)
    (buf/write-f32 dview (+ offset 24) right)
    (buf/write-f32 dview (+ offset 28) bottom)
    (buf/write-f32 dview (+ offset 32) left)
    (write-stroke-fill! dview (+ offset STROKE-HEADER-U8-SIZE) stroke)
    (+ offset STROKE-HEADER-U8-SIZE types.fills.impl/FILL-U8-SIZE)))

(defn- visible-strokes
  [shape]
  (let [type (dm/get-prop shape :type)]
    (if (= type :group)
      []
      (into [] (remove :hidden) (or (get shape :strokes) [])))))

(defn- write-strokes-section!
  [dview offset strokes]
  (buf/write-u32 dview offset (count strokes))
  (reduce (fn [o s] (write-stroke! dview o s))
          (+ offset 4)
          strokes))

(defn write-shape-payload!
  "Serialize one shape's structural payload into `dview` starting at `offset`
  (payload only — no length prefix). Returns the offset after the payload.

  Options:
  - `:include-layout?` — when true, emit FLEX + LAYOUT-ITEM (workspace cold load).
  - `:include-fills-strokes?` — when true, emit FILLS + STROKES sections."
  [dview offset shape {:keys [include-layout? include-fills-strokes?]
                       :or {include-layout? false
                            include-fills-strokes? false}}]
  (let [shape-type (dm/get-prop shape :type)
        children   (into [] (filter uuid?) (get shape :shapes))
        blur       (get shape :blur)
        bg-blur    (get shape :background-blur)
        shadows    (or (get shape :shadow) [])
        masked?    (and (= shape-type :group) (boolean (get shape :masked-group)))
        bool-type  (when (= shape-type :bool) (get shape :bool-type))
        grow-type  (when (= shape-type :text) (get shape :grow-type))
        flex?      (and include-layout? (ctl/flex-layout? shape))
        layout-item? include-layout?
        strokes    (when include-fills-strokes? (visible-strokes shape))

        mask (cond-> 0
               true (bit-or SECTION-CHILDREN)
               (some? blur) (bit-or SECTION-BLUR-LAYER)
               (some? bg-blur) (bit-or SECTION-BLUR-BG)
               (seq shadows) (bit-or SECTION-SHADOWS)
               (= shape-type :group) (bit-or SECTION-MASKED)
               (some? bool-type) (bit-or SECTION-BOOL-TYPE)
               (some? grow-type) (bit-or SECTION-GROW-TYPE)
               flex? (bit-or SECTION-FLEX)
               layout-item? (bit-or SECTION-LAYOUT-ITEM)
               include-fills-strokes? (bit-or SECTION-FILLS)
               include-fills-strokes? (bit-or SECTION-STROKES))

        offset (write-base-props! dview offset shape)
        _      (buf/write-u32 dview offset mask)
        offset (+ offset 4)

        offset (let [o offset]
                 (buf/write-u32 dview o (count children))
                 (reduce (fn [o id] (write-uuid! dview o id))
                         (+ o 4)
                         children))

        offset (cond-> offset
                 (some? blur)
                 (as-> o (write-blur! dview o blur)))

        offset (cond-> offset
                 (some? bg-blur)
                 (as-> o (write-blur! dview o bg-blur)))

        offset (cond-> offset
                 (seq shadows)
                 (as-> o
                       (do
                         (buf/write-u32 dview o (count shadows))
                         (reduce (fn [o s] (write-shadow! dview o s))
                                 (+ o 4)
                                 shadows))))

        offset (cond-> offset
                 (= shape-type :group)
                 (as-> o
                       (do (buf/write-u8 dview o (if masked? 1 0))
                           (+ o 4))))

        offset (cond-> offset
                 (some? bool-type)
                 (as-> o
                       (do (buf/write-u8 dview o (sr/translate-bool-type bool-type))
                           (+ o 4))))

        offset (cond-> offset
                 (some? grow-type)
                 (as-> o
                       (do (buf/write-u8 dview o (sr/translate-grow-type grow-type))
                           (+ o 4))))

        ;; FLEX before LAYOUT-ITEM (Rust clears layout on flex)
        offset (cond-> offset
                 flex?
                 (as-> o (write-flex! dview o shape)))

        offset (cond-> offset
                 layout-item?
                 (as-> o (write-layout-item! dview o shape)))

        offset (cond-> offset
                 include-fills-strokes?
                 (as-> o (write-fills-section! dview o (get shape :fills))))

        offset (cond-> offset
                 include-fills-strokes?
                 (as-> o (write-strokes-section! dview o strokes)))]
    offset))

(defn- payload-byte-size
  [shape {:keys [include-layout? include-fills-strokes?]
          :or {include-layout? false include-fills-strokes? false}}]
  (let [children   (into [] (filter uuid?) (get shape :shapes))
        shadows    (or (get shape :shadow) [])
        shape-type (dm/get-prop shape :type)
        blur       (get shape :blur)
        bg-blur    (get shape :background-blur)
        flex?      (and include-layout? (ctl/flex-layout? shape))
        fills-size (if include-fills-strokes?
                     (types.fills/get-byte-size (types.fills/coerce (or (get shape :fills) [])))
                     0)
        strokes    (when include-fills-strokes? (visible-strokes shape))
        strokes-size (if include-fills-strokes?
                       (+ 4 (* (count strokes)
                               (+ STROKE-HEADER-U8-SIZE types.fills.impl/FILL-U8-SIZE)))
                       0)]
    (+ BASE-PROPS-SIZE
       4 ;; mask
       (+ 4 (* 16 (count children)))
       (if (some? blur) 8 0)
       (if (some? bg-blur) 8 0)
       (if (seq shadows) (+ 4 (* 24 (count shadows))) 0)
       (if (= shape-type :group) 4 0)
       (if (and (= shape-type :bool) (some? (get shape :bool-type))) 4 0)
       (if (and (= shape-type :text) (some? (get shape :grow-type))) 4 0)
       (if flex? 32 0)
       (if include-layout? 40 0)
       fills-size
       strokes-size)))

(defn- encode-shape-record
  "Returns a Uint8Array: [u32 payload_len][payload]."
  [shape opts]
  (let [capacity (+ 4 (payload-byte-size shape opts))
        buffer   (js/ArrayBuffer. capacity)
        dview    (js/DataView. buffer)
        end      (write-shape-payload! dview 4 shape opts)
        payload-len (- end 4)]
    (assert (= end capacity)
            (str "upload record size mismatch: wrote " end " expected " capacity))
    (buf/write-u32 dview 0 payload-len)
    (js/Uint8Array. buffer 0 end)))

(defn flush-shapes-batch!
  "Upload `shapes` as one `_set_shapes_batch` call.
   `opts` passed to each record writer (`:include-layout?`,
   `:include-fills-strokes?`)."
  [shapes opts]
  (when (and (wasm/live?) (seq shapes))
    (let [records (mapv #(encode-shape-record % opts) shapes)
          total   (reduce (fn [acc ^js u8] (+ acc (.-byteLength u8))) 4 records)
          offset  (mem/alloc total)
          heap    (mem/get-heap-u8)
          dview   (js/DataView. (.-buffer heap))]
      (buf/write-u32 dview offset (count records))
      (reduce (fn [o ^js u8]
                (.set heap u8 o)
                (+ o (.-byteLength u8)))
              (+ offset 4)
              records)
      (h/call wasm/internal-module "_set_shapes_batch")
      nil)))

(defn set-shape-upload!
  "Single-shape structural upload (enlarged blob, one FFI)."
  ([shape]
   (set-shape-upload! shape {:include-layout? false}))
  ([shape opts]
   (flush-shapes-batch! [shape] opts)))
