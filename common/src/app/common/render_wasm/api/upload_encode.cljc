;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.api.upload-encode
  "Pure structural encode for `_set_shapes_batch` (JVM + JS).

  No WASM / Emscripten dependency. Browser flush lives in
  `app.common.render-wasm.api.upload`."
  (:require
   [app.common.buffer :as buf]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.render-wasm.enums-data :as enums]
   [app.common.render-wasm.serializers.color :as sr-clr]
   [app.common.types.fills :as types.fills]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

;; v2: JVM UUID layout fixed to LE u32 quartet (matches CLJS/WASM).
(def ^:const PROTOCOL-VERSION "wasm-shapes-batch/v2")

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

(def ^:const STROKE-HEADER-U8-SIZE 36)
(def ^:const STROKE-ALIGN-CENTER 0)
(def ^:const STROKE-ALIGN-INNER 1)
(def ^:const STROKE-ALIGN-OUTER 2)

(defn ->bytes
  "Return a platform byte array / Uint8Array for a buffer written with
  `app.common.buffer`."
  [buffer]
  #?(:clj  (let [^java.nio.ByteBuffer bb buffer
                 arr (byte-array (.capacity bb))]
             (.position bb 0)
             (.get bb arr)
             (.position bb 0)
             arr)
     :cljs (js/Uint8Array. (.-buffer ^js buffer)
                           (.-byteOffset ^js buffer)
                           (.-byteLength ^js buffer))))

(defn- copy-u8!
  [dst-view dst-offset src-view src-offset nbytes]
  #?(:clj
     (buf/copy-bytes src-view src-offset nbytes dst-view dst-offset)
     :cljs
     (let [src (js/Uint8Array. (.-buffer ^js src-view)
                               (+ (.-byteOffset ^js src-view) src-offset)
                               nbytes)
           dst (js/Uint8Array. (.-buffer ^js dst-view)
                               (+ (.-byteOffset ^js dst-view) dst-offset)
                               nbytes)]
       (.set dst src))))

(defn- write-uuid!
  "Write a UUID in the WASM `_set_shapes_batch` layout: four little-endian
  u32 words matching `uuid/get-u32` / `uuid_from_u32_quartet`.

  Do not use `buf/write-uuid` on the JVM here: that helper stores two
  big-endian longs (canonical UUID bytes). CLJS `buf/write-uuid` already
  emits the LE u32 quartet WASM expects; a BE long layout makes every
  shape id diverge from host `use-shape`, so text/path content never
  attaches to the shapes the batch tree renders."
  [dview offset id]
  #?(:cljs
     (buf/write-uuid dview offset id)
     :clj
     (let [msb (.getMostSignificantBits ^java.util.UUID id)
           lsb (.getLeastSignificantBits ^java.util.UUID id)
           a   (unsigned-bit-shift-right msb 32)
           b   (bit-and msb 0xffffffff)
           c   (unsigned-bit-shift-right lsb 32)
           d   (bit-and lsb 0xffffffff)]
       (buf/write-u32 dview offset a)
       (buf/write-u32 dview (+ offset 4) b)
       (buf/write-u32 dview (+ offset 8) c)
       (buf/write-u32 dview (+ offset 12) d)))
  (+ offset 16))

(defn- write-base-props!
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
        blend-mode   (enums/translate-blend-mode (get shape :blend-mode))
        constraint-h (enums/translate-constraint-h (or (get shape :constraints-h) :none))
        constraint-v (enums/translate-constraint-v (or (get shape :constraints-v) :none))
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
    (buf/write-u8 dview (+ offset 32) (enums/translate-shape-type shape-type))
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
    (buf/write-u8 dview (+ offset 20) (enums/translate-shadow-style (get shadow :style)))
    (buf/write-u8 dview (+ offset 21) (if (get shadow :hidden) 1 0))
    (+ offset 24)))

(defn- write-flex!
  [dview offset shape]
  (let [dir             (-> (get shape :layout-flex-dir :row)
                            (enums/translate-layout-flex-dir))
        gap              (get shape :layout-gap)
        row-gap          (get gap :row-gap 0)
        column-gap       (get gap :column-gap 0)
        align-items      (-> (get shape :layout-align-items)
                             enums/translate-layout-align-items)
        align-content    (-> (get shape :layout-align-content)
                             enums/translate-layout-align-content)
        justify-items    (-> (get shape :layout-justify-items)
                             enums/translate-layout-justify-items)
        justify-content  (-> (get shape :layout-justify-content)
                             enums/translate-layout-justify-content)
        wrap-type        (-> (get shape :layout-wrap-type)
                             enums/translate-layout-wrap-type)
        padding          (get shape :layout-padding)
        padding-top      (get padding :p1 0)
        padding-right    (get padding :p2 0)
        padding-bottom   (get padding :p3 0)
        padding-left     (get padding :p4 0)]
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
        h-sizing      (-> (get shape :layout-item-h-sizing)
                          enums/translate-layout-sizing)
        v-sizing      (-> (get shape :layout-item-v-sizing)
                          enums/translate-layout-sizing)
        align-self    (-> (get shape :layout-item-align-self)
                          enums/translate-align-self)
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

(defn- fills-data-byte-size
  [fills]
  (+ 4 (* (count fills) types.fills.impl/FILL-U8-SIZE)))

(defn- write-fills-section!
  [dview offset fills]
  (let [fills  (types.fills/coerce (or fills []))
        nbytes (fills-data-byte-size fills)
        src    (.-dbuffer fills)]
    (copy-u8! dview offset src 0 nbytes)
    (+ offset nbytes)))

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
  (let [width      (or (:stroke-width stroke) 1.0)
        style      (-> stroke :stroke-style enums/translate-stroke-style)
        align      (case (:stroke-alignment stroke)
                     :inner STROKE-ALIGN-INNER
                     :outer STROKE-ALIGN-OUTER
                     STROKE-ALIGN-CENTER)
        cap-start  (-> stroke :stroke-cap-start enums/translate-stroke-cap)
        cap-end    (-> stroke :stroke-cap-end enums/translate-stroke-cap)
        dash       (or (:stroke-dash stroke) -1)
        gap        (or (:stroke-gap stroke) -1)
        top        (or (:stroke-width-top stroke) width)
        right      (or (:stroke-width-right stroke) width)
        bottom     (or (:stroke-width-bottom stroke) width)
        left       (or (:stroke-width-left stroke) width)
        has-sides? (not= top right bottom left)]

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
  (payload only — no length prefix). Returns the offset after the payload."
  [dview offset shape {:keys [include-layout? include-fills-strokes?]
                       :or {include-layout? false
                            include-fills-strokes? false}}]
  (let [shape-type   (dm/get-prop shape :type)
        children     (into [] (filter uuid?) (get shape :shapes))
        blur         (get shape :blur)
        bg-blur      (get shape :background-blur)
        shadows      (or (get shape :shadow) [])
        masked?      (and (= shape-type :group) (boolean (get shape :masked-group)))
        bool-type    (when (= shape-type :bool) (get shape :bool-type))
        grow-type    (when (= shape-type :text) (get shape :grow-type))
        flex?        (and include-layout? (ctl/flex-layout? shape))
        layout-item? include-layout?
        strokes      (when include-fills-strokes? (visible-strokes shape))

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
                       (do (buf/write-u8 dview o (enums/translate-bool-type bool-type))
                           (+ o 4))))

        offset (cond-> offset
                 (some? grow-type)
                 (as-> o
                       (do (buf/write-u8 dview o (enums/translate-grow-type grow-type))
                           (+ o 4))))

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
                     (fills-data-byte-size
                      (types.fills/coerce (or (get shape :fills) [])))
                     0)
        strokes    (when include-fills-strokes? (visible-strokes shape))
        strokes-size (if include-fills-strokes?
                       (+ 4 (* (count strokes)
                               (+ STROKE-HEADER-U8-SIZE types.fills.impl/FILL-U8-SIZE)))
                       0)]
    (+ BASE-PROPS-SIZE
       4
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

(defn encode-shape-record
  "Returns platform bytes for `[u32 payload_len][payload]`."
  [shape opts]
  (let [capacity (+ 4 (payload-byte-size shape opts))
        dview    (buf/allocate capacity)
        end      (write-shape-payload! dview 4 shape opts)
        payload-len (- end 4)]
    (assert (= end capacity)
            (str "upload record size mismatch: wrote " end " expected " capacity))
    (buf/write-u32 dview 0 payload-len)
    (->bytes dview)))

(defn encode-shapes-batch
  "Encode `shapes` as one `_set_shapes_batch` buffer. Returns platform bytes.
   Opts: `:include-layout?`, `:include-fills-strokes?`."
  [shapes opts]
  (if-not (seq shapes)
    (->bytes (let [b (buf/allocate 4)]
               (buf/write-u32 b 0 0)
               b))
    (let [records (mapv #(encode-shape-record % opts) shapes)
          total   (reduce (fn [acc u8]
                            (+ acc #?(:clj  (alength ^bytes u8)
                                      :cljs (.-byteLength ^js u8))))
                          4
                          records)
          dview   (buf/allocate total)]
      (buf/write-u32 dview 0 (count records))
      (reduce (fn [o u8]
                (let [len #?(:clj  (alength ^bytes u8)
                             :cljs (.-byteLength ^js u8))]
                  #?(:clj
                     (let [src (java.nio.ByteBuffer/wrap ^bytes u8)]
                       (.order src java.nio.ByteOrder/LITTLE_ENDIAN)
                       (buf/copy-bytes src 0 len dview o))
                     :cljs
                     (.set (js/Uint8Array. (.-buffer ^js dview)
                                           (+ (.-byteOffset ^js dview) o)
                                           len)
                           u8))
                  (+ o len)))
              4
              records)
      (->bytes dview))))

(defn shapes-in-tree-order
  "Shapes sorted parents-before-children (workspace cold-load order)."
  [objects]
  (if (contains? objects uuid/zero)
    (let [ordered-ids (cfh/get-children-ids-with-self objects uuid/zero)]
      (into [] (keep #(get objects %)) ordered-ids))
    (let [top-level-ids (->> (vals objects)
                             (filter (fn [shape]
                                       (not (contains? objects (:parent-id shape)))))
                             (map :id))
          all-ordered-ids (into []
                                (mapcat #(cfh/get-children-ids-with-self objects %))
                                top-level-ids)]
      (into [] (keep #(get objects %)) all-ordered-ids))))

(defn encode-page-objects
  "Encode page `:objects` map for cold-load (layout + fills/strokes)."
  [objects]
  (encode-shapes-batch
   (shapes-in-tree-order objects)
   {:include-layout? true
    :include-fills-strokes? true}))
