;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm
  "Headless driver for the render-wasm module under Node.

  This is the GPU-free counterpart of the browser's
  `app.render-wasm.api`: it loads the emscripten artifact in the Node
  process (no WebGL), boots it via `init_headless`, and exposes the
  minimal surface the exporter needs — font provisioning and shape
  rendering to PNG/PDF bytes.

  The shape *serialization* is reused from the portable render-wasm leaves
  (`app.render-wasm.{wasm,mem,helpers,serializers,api.shapes,...}`); this
  namespace only owns the Node runtime + the headless render/export calls.
  The CLJS↔WASM binary protocol therefore stays identical to the editor —
  no second implementation, no drift.

  Requires render-wasm built with `-sENVIRONMENT=web,node`."
  (:require
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.wasm :as wasm]
   [promesa.core :as p]
   [shadow.esm :refer [dynamic-import]]))

(def ^:private default-viewport-width 1920)
(def ^:private default-viewport-height 1080)

;; render_shape_raster / render_shape_pixels result header: [len u32][w u32][h u32].
(def ^:private RASTER-HEADER-BYTES 12)
;; get_fonts_for_shape entry: [uuid 16 bytes][weight u32][style u32].
(def ^:private FONT-ENTRY-BYTES 24)

(defn- artifact-dir
  []
  (cf/get :wasm-dir "../frontend/resources/public/js"))

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
   (let [dir       (artifact-dir)
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
  (let [module wasm/internal-module
        buf    (uuid/get-u32 shape-id) ;; resolved from app.render-wasm leaves
        offset (h/call module "_get_fonts_for_shape"
                       (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3))
        heap32 (mem/get-heap-u32)
        n      (aget heap32 (mem/->offset-32 offset))]
    (let [entries
          (vec
           (for [i (range n)]
             (let [base (+ offset 4 (* i FONT-ENTRY-BYTES))
                   u32  (fn [o] (aget heap32 (mem/->offset-32 (+ base o))))]
               {:id     #js [(u32 0) (u32 4) (u32 8) (u32 12)]
                :weight (u32 16)
                :style  (u32 20)})))]
      (mem/free)
      entries)))

(defn store-font!
  "Uploads one font's TTF bytes into the WASM font store, keyed by the family
  (uuid quartet + weight + style). `font-bytes` is a Uint8Array/Buffer."
  [{:keys [id weight style emoji? fallback?]} font-bytes]
  (let [module wasm/internal-module
        size   (.-byteLength font-bytes)
        ptr    (h/call module "_alloc_bytes" size)
        heap   (mem/get-heap-u8)]
    (.set heap (js/Uint8Array. font-bytes) ptr)
    (h/call module "_store_font"
            (aget id 0) (aget id 1) (aget id 2) (aget id 3)
            weight style (boolean emoji?) (boolean fallback?))))

(defn clear-fonts!
  "Resets the WASM font store. Must be called once per render request because
  the shared module would otherwise accumulate fonts across requests."
  []
  (h/call wasm/internal-module "_clear_fonts"))

(defn update-text-layout!
  "Recomputes a text shape's layout with the currently provisioned fonts. Text is
  laid out at serialize time using the fallback font (real fonts aren't uploaded
  yet), so this must run again after `provision-fonts!` or glyph metrics/line
  breaks are wrong."
  [shape-id]
  (let [buf (uuid/get-u32 shape-id)]
    (h/call wasm/internal-module "_update_shape_text_layout_for"
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
  "Evicts least-recently-used images until the module's image store retains at
  most `max-mb` megabytes. Called between requests, so an image can never
  disappear under a running render; evicted images are re-provisioned by any
  later request that needs them. Returns the number of evicted images."
  [max-mb]
  (h/call wasm/internal-module "_evict_images_to_budget" max-mb))

(defn provision-fonts!
  "Resolves and uploads every font needed by `shape-id`. `resolve-font` is an
  injected fn of the family map -> promise of TTF bytes (or nil to skip). This
  keeps the font *source* (gfonts proxy / custom assets / backend) out of the
  driver."
  [shape-id resolve-font]
  (->> (fonts-for-shape shape-id)
       (map (fn [family]
              (->> (resolve-font family)
                   (p/fmap (fn [bytes] (when bytes (store-font! family bytes)))))))
       (p/all)))

;; --- RENDER

(defn- render-call
  [fn-name shape-id scale]
  (let [module wasm/internal-module
        buf    (uuid/get-u32 shape-id)
        offset (h/call module fn-name
                       (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3)
                       scale)
        heap32 (mem/get-heap-u32)
        len    (aget heap32 (mem/->offset-32 offset))
        bytes  (read-result-bytes (+ offset RASTER-HEADER-BYTES) len)]
    (mem/free)
    bytes))

(defn render-shape-raster
  "Renders the shape subtree to PNG bytes (Uint8Array) on a CPU surface."
  [shape-id scale]
  (render-call "_render_shape_raster" shape-id scale))

(defn render-shape-pdf
  "Renders the shape subtree to PDF bytes (Uint8Array)."
  [shape-id scale]
  ;; PDF result header is [len u32] only (no w/h); handled separately.
  (let [module wasm/internal-module
        buf    (uuid/get-u32 shape-id)
        offset (h/call module "_render_shape_pdf"
                       (aget buf 0) (aget buf 1) (aget buf 2) (aget buf 3)
                       scale)
        heap32 (mem/get-heap-u32)
        len    (aget heap32 (mem/->offset-32 offset))
        bytes  (read-result-bytes (+ offset 4) len)]
    (mem/free)
    bytes))
