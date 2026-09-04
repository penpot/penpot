;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.wasm
  "Headless driver for the render-wasm module under Node: the GPU-free
  counterpart of `app.render-wasm.api`. Loads the emscripten artifact, boots it
  via `init_headless`, and exposes font provisioning + shape rendering.

  Serialization is reused from the portable render-wasm leaves, so this
  namespace owns only the Node runtime and the headless render calls.

  Requires render-wasm built with `-sENVIRONMENT=web,node`."
  (:require
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]
   ;; Required for side effects: binds the generated enums.
   [app.wasm.enums]
   [promesa.core :as p]
   [shadow.esm :refer [dynamic-import]]))

(def ^:private default-viewport-width 1920)
(def ^:private default-viewport-height 1080)

;; render_shape_raster / render_shape_pixels result header: [len u32][w u32][h u32].
(def ^:private RASTER-HEADER-BYTES 12)
;; render_shape_pdf / render_shape_svg result header: [len u32] only.
(def ^:private LEN-HEADER-BYTES 4)
;; get_fonts_for_shape entry: [uuid 16 bytes][weight u32][style u32].
(def ^:private FONT-ENTRY-BYTES 24)

(def artifact-dir
  "Built render-wasm artifact, relative to the process working directory. Same
  path in devenv and inside the bundle, so it is a constant."
  "resources/wasm")

(def image-cache-size
  "Byte budget the image store is trimmed to between requests."
  (* 256 1024 1024))

(defn- read-result-bytes
  "Reads `len` bytes from the WASM heap starting at `offset`, copying them out
  (via `.slice`) before the buffer is freed."
  [offset len]
  (.slice (mem/get-heap-u8) offset (+ offset len)))

;; --- MODULE LIFECYCLE

(defn init!
  "Loads the render-wasm artifact under Node and boots it headless. Sets the
  shared `wasm/internal-module` so the portable serialization leaves work.
  Idempotent-ish: callers should hold the returned module."
  ([] (init! default-viewport-width default-viewport-height))
  ([width height]
   (let [dir       artifact-dir
         js-path   (path/resolve dir "render-wasm.js")
         wasm-path (path/resolve dir "render-wasm.wasm")
         wasm-bytes (fs/readFileSync wasm-path)]
     (l/info :hint "loading render-wasm (headless)" :js js-path)
     ;; shadow-cljs :esm — use its dynamic-import helper (raw `js/import`
     ;; compiles to an undefined `import$`).
     (->> (dynamic-import (str "file://" js-path))
          (p/mcat
           (fn [mod]
             (let [factory (unchecked-get mod "default")]
               (factory
                #js {;; Bypass the web fetch loader: instantiate from local bytes.
                     :instantiateWasm
                     (fn [imports success]
                       (-> (js/WebAssembly.instantiate wasm-bytes imports)
                           (.then (fn [result] (success (.-instance result)))))
                       #js {})
                     :locateFile (fn [p] (path/resolve dir p))
                     :printErr   (fn [s] (l/warn :wasm s))}))))
          (p/fmap
           (fn [module]
             (set! wasm/internal-module module)
             (h/call module "_init_headless" width height)
             (set! wasm/context-initialized? true)
             (l/info :hint "render-wasm headless module ready" :width width :height height)
             module))))))

;; --- FONT PROVISIONING (on demand, mirrors the browser)

(defn fonts-for-shape
  "Returns the distinct font families needed to render the subtree rooted at
  `shape-id` as a vector of {:id <uuid-u32x4> :weight :style}. Equivalent to
  the browser's `get-content-fonts`, but read from the loaded WASM tree."
  [shape-id]
  (let [module  wasm/internal-module
        buf     (uuid/get-u32 shape-id) ;; resolved from app.render-wasm leaves
        offset  (h/call module "_get_fonts_for_shape"
                        (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3))
        heap32  (mem/get-heap-u32)
        n       (aget heap32 (mem/->offset-32 offset))
        ;; `vec` must stay eager: it reads the result buffer, and the
        ;; `mem/free` below invalidates these offsets.
        entries (vec
                 (for [i (range n)]
                   (let [base (+ offset 4 (* i FONT-ENTRY-BYTES))
                         u32  (fn [o] (aget heap32 (mem/->offset-32 (+ base o))))]
                     {:id     #js [(u32 0) (u32 4) (u32 8) (u32 12)]
                      :weight (u32 16)
                      :style  (u32 20)})))]
    (mem/free)
    entries))

(defn- font-key
  "Value key for a family map. Its `:id` is a JS array, so the map itself can't
  be compared by value."
  [{:keys [id weight style]}]
  [(aget id 0) (aget id 1) (aget id 2) (aget id 3) weight style])

(defn fonts-for-shapes
  "Distinct font families needed by every subtree in `shape-ids`. Objects in one
  export overwhelmingly share families, so deduping here means one download and
  one `_store_font` per family rather than one per object."
  [shape-ids]
  (into [] (comp (mapcat fonts-for-shape)
                 (d/distinct-xf font-key))
        shape-ids))

(defn store-font!
  "Uploads one font's TTF bytes into the WASM font store, keyed by the family
  (uuid quartet + weight + style). `font-bytes` is a Uint8Array/Buffer.

  Does NOT call `mem/free` — `store_font` (and likewise `store_image` below)
  releases the global buffer itself on the Rust side. Freeing again here would
  drop a buffer a later writer already owns."
  [{:keys [id weight style emoji? fallback?]} font-bytes]
  (let [module wasm/internal-module
        size   (.-byteLength font-bytes)
        ptr    (h/call module "_alloc_bytes" size)
        heap   (mem/get-heap-u8)]
    (.set heap (js/Uint8Array. font-bytes) ptr)
    (h/call module "_store_font"
            (aget id 0) (aget id 1) (aget id 2) (aget id 3)
            weight style (boolean emoji?) (boolean fallback?))))

(defn store-font-url!
  "Registers the public URL a font family was loaded from. The SVG export emits
  one `@font-face` per family from these, and skips families without one, so
  this must run for every family `store-font!` uploads.

  Does NOT call `mem/free`, for the same reason as `store-font!`."
  [{:keys [id weight style]} url]
  (let [bytes (js/Buffer.from url "utf-8")
        ptr   (mem/alloc (.-byteLength bytes))]
    (mem/write-buffer ptr (mem/get-heap-u8) bytes)
    (h/call wasm/internal-module "_store_font_url"
            (aget id 0) (aget id 1) (aget id 2) (aget id 3)
            weight style)))

(defn clear-fonts!
  "Resets the WASM font store. Must be called once per render request because
  the shared module would otherwise accumulate fonts across requests."
  []
  (h/call wasm/internal-module "_clear_fonts"))

(defn update-text-layout!
  "Recomputes a text shape's layout with the currently provisioned fonts. Text is
  laid out at serialize time using the fallback font (real fonts aren't uploaded
  yet), so this must run again after `provision-fonts!` or glyph metrics/line
  breaks are wrong.

  Forced, because provisioning a font changes nothing `update_layout` keys on:
  it early-returns while the content is unchanged and the layout still matches
  its container, which is exactly the case here."
  [shape-id]
  (let [buf (uuid/get-u32 shape-id)]
    (h/call wasm/internal-module "_force_update_shape_text_layout_for"
            (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3))))

(defn image-cached?
  "True when the module's image store already holds this image (full size).
  The store is NOT reset between requests, so previously provisioned images
  can be reused instead of refetched."
  [image-id]
  (let [buf (uuid/get-u32 image-id)]
    (not (zero? (h/call wasm/internal-module "_is_image_cached"
                        (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3)
                        false)))))

(defn store-image!
  "Uploads one image's *encoded* bytes (PNG/JPEG — Skia decodes, no WebGL) into
  the WASM image store via `_store_image`. Buffer layout matches the Rust reader:
  [shape uuid 16][image uuid 16][is_thumbnail u32][encoded bytes]. Images are
  keyed by image uuid, so the shape uuid is left zero. `image-bytes` is an
  ArrayBuffer/Buffer/Uint8Array."
  [image-id image-bytes]
  (let [module wasm/internal-module
        img-u8 (js/Uint8Array. image-bytes)
        size   (.-byteLength img-u8)
        total  (+ 36 size)
        ptr    (h/call module "_alloc_bytes" total)
        heap   (mem/get-heap-u8)
        dview  (js/DataView. (.-buffer heap))
        quart  (uuid/get-u32 image-id)]
    ;; shape uuid [0..16) = 0 (images are keyed by image uuid only)
    (.setUint32 dview (+ ptr 0) 0 true)
    (.setUint32 dview (+ ptr 4) 0 true)
    (.setUint32 dview (+ ptr 8) 0 true)
    (.setUint32 dview (+ ptr 12) 0 true)
    ;; image uuid [16..32) — 4 LE u32 (matches common `buffer/write-uuid`, which
    ;; the fill path uses, so it hashes to the same key the fill references)
    (.setUint32 dview (+ ptr 16) (aget quart 0) true)
    (.setUint32 dview (+ ptr 20) (aget quart 1) true)
    (.setUint32 dview (+ ptr 24) (aget quart 2) true)
    (.setUint32 dview (+ ptr 28) (aget quart 3) true)
    ;; is_thumbnail [32..36) = 0
    (.setUint32 dview (+ ptr 32) 0 true)
    ;; encoded bytes [36..)
    (.set heap img-u8 (+ ptr 36))
    (h/call module "_store_image")))

(defn evict-images!
  "Evicts least-recently-used images until the store retains at most `max-bytes`
  bytes. Returns the number evicted."
  [max-bytes]
  (h/call wasm/internal-module "_evict_images_to_budget" max-bytes))

(defn provision-fonts!
  "Resolves and uploads every font needed by `shape-ids`, each family fetched
  once. `resolve-font` is an injected fn of the family map -> promise of TTF
  bytes (or nil to skip); optional `font-url` is a fn of the family map -> the
  public URL those bytes came from. This keeps the font *source* (gfonts proxy
  / custom assets / backend) out of the driver."
  [shape-ids resolve-font & {:keys [font-url]}]
  (->> (fonts-for-shapes shape-ids)
       (map (fn [family]
              (->> (resolve-font family)
                   (p/fmap (fn [bytes]
                             (when bytes
                               (store-font! family bytes)
                               (when-let [url (when font-url (font-url family))]
                                 (store-font-url! family url))))))))
       (p/all)))

;; --- RENDER

(defn- read-render-result
  "Copies the encoded payload out of a `_render_shape_*` result buffer and frees
  it. `header-bytes` is the size of the header preceding the payload."
  [offset header-bytes]
  (let [heap32 (mem/get-heap-u32)
        len    (aget heap32 (mem/->offset-32 offset))
        bytes  (read-result-bytes (+ offset header-bytes) len)]
    (mem/free)
    bytes))

(defn render-shape-raster
  "Renders the shape subtree to encoded image bytes (Uint8Array) on a CPU
  surface. `format` is :png, :jpeg or :webp; jpeg is flattened onto white on
  the Rust side, since it has no alpha channel."
  [shape-id scale format]
  (let [buf (uuid/get-u32 shape-id)]
    (-> (h/call wasm/internal-module "_render_shape_raster"
                (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3)
                scale (sr/translate-raster-format format))
        (read-render-result RASTER-HEADER-BYTES))))

(defn render-shape-pdf
  "Renders the shape subtree to PDF bytes (Uint8Array)."
  [shape-id scale]
  (let [buf (uuid/get-u32 shape-id)]
    (-> (h/call wasm/internal-module "_render_shape_pdf"
                (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3)
                scale)
        (read-render-result LEN-HEADER-BYTES))))

(defn render-shape-svg
  "Renders the shape subtree to SVG markup bytes (Uint8Array)."
  [shape-id scale]
  (let [buf (uuid/get-u32 shape-id)]
    (-> (h/call wasm/internal-module "_render_shape_svg"
                (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3)
                scale)
        (read-render-result LEN-HEADER-BYTES))))
