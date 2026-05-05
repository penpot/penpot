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
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.common.types.fills :as types.fills]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.types.path :as path]
   [app.common.types.path.impl :as path.impl]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.notifications :as ntf]
   [app.main.data.render-wasm :as drw]
   [app.main.data.workspace.texts-v3 :as texts]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.shapes.text]
   [app.render-wasm.api.fonts :as f]
   [app.render-wasm.api.shapes :as shapes]
   [app.render-wasm.api.texts :as t]
   [app.render-wasm.api.webgl :as webgl]
   [app.render-wasm.deserializers :as dr]
   [app.render-wasm.gesture :as wasm-gesture]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.mem.heap32 :as mem.h32]
   [app.render-wasm.performance :as perf]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.svg-filters :as svg-filters]
   [app.render-wasm.text-editor :as text-editor]
   [app.render-wasm.wasm :as wasm]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.functions :as fns]
   [app.util.globals :as ug]
   [app.util.i18n :refer [tr]]
   [app.util.modules :as mod]
   [app.util.text.content :as tc]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(def use-dpr? (contains? cf/flags :render-wasm-dpr))

;; --- Page transition state (WASM viewport)
;;
;; Goal: avoid showing tile-by-tile rendering during page switches (and initial load),
;; by keeping a blurred snapshot overlay visible until WASM dispatches
;; `penpot:wasm:tiles-complete`.
;;
;; - `page-transition?`: true while the overlay should be considered active.
;; - `transition-image-url*`: URL used by the UI overlay (usually `blob:` from the
;;   current WebGL canvas snapshot; on initial load it may be a tiny SVG data-url
;;   derived from the page background color).
;; - `transition-epoch*`: monotonic counter used to ignore stale async work/events
;;   when the user clicks pages rapidly (A -> B -> C).
;; - `transition-tiles-handler*`: the currently installed DOM event handler for
;;   `penpot:wasm:tiles-complete`, so we can remove/replace it safely.
(defonce page-transition? (atom false))
(defonce transition-image-url* (atom nil))
(defonce transition-epoch* (atom 0))
(defonce transition-tiles-handler* (atom nil))

(def ^:private transition-blur-css "blur(4px)")
(defonce last-reload-payload* (atom nil))

(defn- set-transition-blur!
  []
  (when-let [canvas ^js wasm/canvas]
    (dom/set-style! canvas "filter" transition-blur-css))
  (when-let [nodes (.querySelectorAll ^js ug/document ".blurrable")]
    (doseq [^js node (array-seq nodes)]
      (dom/set-style! node "filter" transition-blur-css))))

(defn- clear-transition-blur!
  []
  (when-let [canvas ^js wasm/canvas]
    (dom/set-style! canvas "filter" ""))
  (when-let [nodes (.querySelectorAll ^js ug/document ".blurrable")]
    (doseq [^js node (array-seq nodes)]
      (dom/set-style! node "filter" ""))))

(defn set-transition-image-from-background!
  "Sets `transition-image-url*` to a data URL representing a solid background color."
  [background]
  (when (string? background)
    (let [svg (str "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'>"
                   "<rect width='1' height='1' fill='" background "'/>"
                   "</svg>")]
      (reset! transition-image-url*
              (str "data:image/svg+xml;charset=utf-8," (js/encodeURIComponent svg))))))

(defn begin-page-transition!
  []
  (reset! page-transition? true)
  (swap! transition-epoch* inc))

(defn end-page-transition!
  []
  (reset! page-transition? false)
  (when-let [prev @transition-tiles-handler*]
    (.removeEventListener ^js ug/document "penpot:wasm:tiles-complete" prev))
  (reset! transition-tiles-handler* nil)
  (reset! transition-image-url* nil)
  (clear-transition-blur!)
  ;; Clear captured pixels so future transitions must explicitly capture again.
  (set! wasm/canvas-snapshot-url nil))

(defn- set-transition-tiles-complete-handler!
  "Installs a tiles-complete handler bound to the current transition epoch.
   Replaces any previous handler so rapid page switching doesn't end the wrong transition."
  [epoch f]
  (when-let [prev @transition-tiles-handler*]
    (.removeEventListener ^js ug/document "penpot:wasm:tiles-complete" prev))
  (letfn [(handler [_]
            (when (= epoch @transition-epoch*)
              (.removeEventListener ^js ug/document "penpot:wasm:tiles-complete" handler)
              (reset! transition-tiles-handler* nil)
              (f)))]
    (reset! transition-tiles-handler* handler)
    (.addEventListener ^js ug/document "penpot:wasm:tiles-complete" handler)))

(defn start-initial-load-transition!
  "Starts a page-transition workflow for initial file open.

   - Sets `page-transition?` to true
   - Installs a tiles-complete handler to end the transition
   - Uses a solid background-color placeholder as the transition image"
  [background]
  ;; If something already toggled `page-transition?` (e.g. legacy init code paths),
  ;; ensure we still have a deterministic placeholder on initial load.
  (when (or (not @page-transition?) (nil? @transition-image-url*))
    (set-transition-image-from-background! background))
  (when-not @page-transition?
    ;; Start transition + bind the tiles-complete handler to this epoch.
    (let [epoch (begin-page-transition!)]
      (set-transition-tiles-complete-handler! epoch end-page-transition!))))

(defn listen-tiles-render-complete-once!
  "Registers a one-shot listener for `penpot:wasm:tiles-complete`, dispatched from WASM
  when a full tile pass finishes."
  [f]
  (.addEventListener ^js ug/document
                     "penpot:wasm:tiles-complete"
                     (fn [_]
                       (f))
                     #js {:once true}))

(defn text-editor-wasm?
  []
  (or (contains? cf/flags :feature-text-editor-wasm)
      (let [runtime-features (get @st/state :features-runtime)
            enabled-features (get @st/state :features)]
        (or (contains? runtime-features "text-editor-wasm/v1")
            (contains? enabled-features "text-editor-wasm/v1")))))

(def ^:const UUID-U8-SIZE 16)
(def ^:const UUID-U32-SIZE (/ UUID-U8-SIZE 4))

;; FIXME: Migrate this as we adjust the DTO structure in wasm
(def ^:const MODIFIER-U8-SIZE 40)
(def ^:const MODIFIER-U32-SIZE (/ MODIFIER-U8-SIZE 4))
(def ^:const MODIFIER-TRANSFORM-U8-OFFSET-SIZE 16)
(def ^:const INPUT-MODIFIER-U8-SIZE 44)
(def ^:const INPUT-MODIFIER-U32-SIZE (/ INPUT-MODIFIER-U8-SIZE 4))

(def ^:const GRID-LAYOUT-ROW-U8-SIZE 8)
(def ^:const GRID-LAYOUT-COLUMN-U8-SIZE 8)
(def ^:const GRID-LAYOUT-CELL-U8-SIZE 36)

(def ^:const MAX_BUFFER_CHUNK_SIZE (* 256 1024))

(def ^:const DEBOUNCE_DELAY_MS 100)

;; Time budget (ms) per chunk of shape processing before yielding to browser
(def ^:private ^:const CHUNK_TIME_BUDGET_MS 8)
;; Threshold below which we use synchronous processing (no chunking overhead)
(def ^:const ASYNC_THRESHOLD 100)

;; Text editor events.
(def ^:const TEXT_EDITOR_EVENT_NONE 0)
(def ^:const TEXT_EDITOR_EVENT_CONTENT_CHANGED 1)
(def ^:const TEXT_EDITOR_EVENT_SELECTION_CHANGED 2)
(def ^:const TEXT_EDITOR_EVENT_STYLES_CHANGED 3)
(def ^:const TEXT_EDITOR_EVENT_NEEDS_LAYOUT 4)

;; Re-export public WebGL functions
(def capture-canvas-snapshot-url webgl/capture-canvas-snapshot-url)
(def draw-thumbnail-to-canvas webgl/draw-thumbnail-to-canvas)

;; Re-export public text editor functions
(def text-editor-focus text-editor/text-editor-focus)
(def text-editor-blur text-editor/text-editor-blur)
(def text-editor-set-cursor-from-offset text-editor/text-editor-set-cursor-from-offset)
(def text-editor-set-cursor-from-point text-editor/text-editor-set-cursor-from-point)
(def text-editor-toggle-overtype-mode text-editor/text-editor-toggle-overtype-mode)
(def text-editor-pointer-down text-editor/text-editor-pointer-down)
(def text-editor-pointer-move text-editor/text-editor-pointer-move)
(def text-editor-pointer-up text-editor/text-editor-pointer-up)
(def text-editor-get-current-styles text-editor/text-editor-get-current-styles)
(def text-editor-has-focus? text-editor/text-editor-has-focus?)
(def text-editor-has-selection? text-editor/text-editor-has-selection?)
(def text-editor-select-all text-editor/text-editor-select-all)
(def text-editor-select-word-boundary text-editor/text-editor-select-word-boundary)
(def text-editor-sync-content text-editor/text-editor-sync-content)

(def dpr
  (if use-dpr? (if (exists? js/window) js/window.devicePixelRatio 1.0) 1.0))

(def noop-fn
  (constantly nil))

;;
(def shape-wrapper-factory nil)

(let [^js ch (js/MessageChannel.)]
  (defn- yield-to-browser
    "Returns a promise that resolves after yielding to the browser's event loop.
     Uses MessageChannel for near-zero delay (avoids setTimeout's 4ms minimum
     after nesting depth > 5). Same technique used by React's scheduler."
    []
    (p/create
     (fn [resolve _reject]
       (set! (.-onmessage (.-port1 ch))
             (fn [_] (resolve nil)))
       (.postMessage (.-port2 ch) nil)))))

;; Based on app.main.render/object-svg
(mf/defc object-svg
  {::mf/props :obj}
  [{:keys [shape] :as props}]
  (let [objects (mf/deref refs/workspace-page-objects)
        shape-wrapper
        (mf/with-memo [shape]
          (shape-wrapper-factory objects))]

    [:svg {:version "1.1"
           :xmlns "http://www.w3.org/2000/svg"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :fill "none"}
     [:& shape-wrapper {:shape shape}]]))

(defn is-text-editor-wasm-enabled
  [state]
  (let [runtime-features (get state :features-runtime)
        enabled-features (get state :features)]
    (or (contains? runtime-features "text-editor-wasm/v1")
        (contains? enabled-features "text-editor-wasm/v1"))))

(defn get-static-markup
  [shape]
  (->
   (mf/element object-svg #js {:shape shape})
   (rds/renderToStaticMarkup)))

;; forward declare helpers so render can call them
(declare request-render)
(declare set-shape-vertical-align fonts-from-text-content)
(declare reload-renderer!)

(defn set-last-reload-payload!
  "Stores the latest payload needed to replay a renderer reload."
  [payload]
  (when (map? payload)
    (reset! last-reload-payload* payload)))

(defn free-gpu-resources
  []
  ;; check if the context has not been lost already or we will get warnings about
  ;; removing objects from a non-current context
  (when (and wasm/context-initialized?
             (not @wasm/context-lost?))
    (h/call wasm/internal-module "_free_gpu_resources")))

;; This should never be called from the outside.
(defn- render
  [timestamp]
  (when (and wasm/context-initialized? (not @wasm/context-lost?))
    (h/call wasm/internal-module "_render" timestamp)

    ;; Update text editor blink (so cursor toggles) using the same timestamp
    (try
      (when (is-text-editor-wasm-enabled @st/state)
        (text-editor/text-editor-update-blink timestamp)
        (text-editor/text-editor-render-overlay)
        ;; Poll for editor events; if any event occurs, trigger a re-render
        (let [ev (text-editor/text-editor-poll-event)]
          (when (and ev (not= ev TEXT_EDITOR_EVENT_NONE))
            ;; When StylesChanged, get the current styles.
            (case ev
              ;; StylesChanged Event
              TEXT_EDITOR_EVENT_STYLES_CHANGED
              (let [current-styles (text-editor/text-editor-get-current-styles)
                    shape-id (text-editor/text-editor-get-active-shape-id)]
                (st/emit! (texts/v3-update-text-editor-styles shape-id current-styles)))

              ;; Default case
              nil)

            (request-render "text-editor-event"))))
      (catch :default e
        (js/console.error "text-editor overlay/update failed:" e)))

    (set! wasm/internal-frame-id nil)
    (ug/dispatch! (ug/event "penpot:wasm:render"))))

(defn render-sync
  []
  (when (and wasm/context-initialized? (not @wasm/context-lost?))
    (h/call wasm/internal-module "_render_sync")
    (set! wasm/internal-frame-id nil)))

(defn render-sync-shape
  [id]
  (when (and wasm/context-initialized? (not @wasm/context-lost?))
    (let [buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_render_sync_shape"
              (aget buffer 0)
              (aget buffer 1)
              (aget buffer 2)
              (aget buffer 3))
      (set! wasm/internal-frame-id nil))))

(defn render-preview!
  "Render a lightweight preview without tile caching.
   Used during progressive loading for fast feedback."
  []
  (when (and wasm/context-initialized? (not @wasm/context-lost?))
    (h/call wasm/internal-module "_render_preview")))


(defonce pending-render (atom false))
(defonce shapes-loading? (atom false))
(defonce deferred-render? (atom false))

(defn- register-deferred-render!
  []
  (reset! deferred-render? true))

(defn request-render
  [_requester]
  (when (and wasm/context-initialized? (not @wasm/context-lost?) (not @wasm/disable-request-render?))
    (if @shapes-loading?
      (register-deferred-render!)
      (when-not @pending-render
        (reset! pending-render true)
        (let [frame-id
              (js/requestAnimationFrame
               (fn [ts]
                 (reset! pending-render false)
                 (set! wasm/internal-frame-id nil)
                 (render ts)))]
          (set! wasm/internal-frame-id frame-id))))))

(defn- begin-shapes-loading!
  []
  (reset! shapes-loading? true)
  (let [frame-id wasm/internal-frame-id
        was-pending @pending-render]
    (when frame-id
      (js/cancelAnimationFrame frame-id)
      (set! wasm/internal-frame-id nil))
    (reset! pending-render false)
    (reset! deferred-render? was-pending)))

(defn- end-shapes-loading!
  []
  (let [was-loading (compare-and-set! shapes-loading? true false)]
    (reset! deferred-render? false)
    ;; Always trigger a render after loading completes
    ;; This ensures shapes are displayed even if no deferred render was requested
    (when was-loading
      (request-render "set-objects:flush"))))

(declare get-text-dimensions)

(defn use-shape
  [id]
  (when wasm/context-initialized?
    (let [buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_use_shape"
              (aget buffer 0)
              (aget buffer 1)
              (aget buffer 2)
              (aget buffer 3)))))

(defn set-shape-text-content
  "This function sets shape text content and returns a stream that loads the needed fonts asynchronously"
  [shape-id content]

  ;; Cache content for text editor sync
  (text-editor/cache-shape-text-content! shape-id content)

  (h/call wasm/internal-module "_clear_shape_text")

  (set-shape-vertical-align (get content :vertical-align))

  (let [fonts         (f/get-content-fonts content)
        fallback-fonts (fonts-from-text-content content true)
        all-fonts (concat fonts fallback-fonts)
        result (f/store-fonts all-fonts)]
    (f/load-fallback-fonts-for-editor! fallback-fonts)
    (h/call wasm/internal-module "_update_shape_text_layout")
    result))

(defn apply-styles-to-selection
  "Apply style attrs to the currently selected text spans.
   Updates the cached content, pushes to WASM, and returns {:shape-id :content} for saving."
  [attrs]
  (let [result (text-editor/apply-styles-to-selection attrs use-shape set-shape-text-content)]
    (request-render "apply-styles-to-selection")
    result))

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
               (when-let [gl (webgl/get-webgl-context)]
                 (let [texture (webgl/create-webgl-texture-from-image gl img)
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
          image-ids (types.fills/get-image-ids fills)
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

            image-ids))))

(defn set-shape-strokes
  [shape-id strokes thumbnail?]
  (h/call wasm/internal-module "_clear_shape_strokes")
  (keep (fn [stroke]
          (when-not (:hidden stroke)
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
                  (h/call wasm/internal-module "_add_shape_stroke_fill")
                  nil)

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
                  (h/call wasm/internal-module "_add_shape_stroke_fill")
                  nil)))))

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

(defn set-layout-data
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
            "_set_layout_data"
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

(defn has-any-layout-prop? [shape]
  (some #(and (keyword? %)
              (str/starts-with? (name %) "layout-"))
        (keys shape)))

(defn clear-layout
  []
  (h/call wasm/internal-module "_clear_shape_layout"))

(defn- set-shape-layout
  [shape]
  (clear-layout)
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
  (if (and wasm/context-initialized? (not @wasm/context-lost?))
    (let [buffer (uuid/get-u32 id)
          result
          (h/call wasm/internal-module "_intersect_position_in_shape"
                  (aget buffer 0)
                  (aget buffer 1)
                  (aget buffer 2)
                  (aget buffer 3)
                  (:x position)
                  (:y position))]
      (= result 1))
    false))

(def render-finish
  (letfn [(do-render []
            ;; Check if context is still initialized before executing
            ;; to prevent errors when navigating quickly
            (when (and wasm/context-initialized? (not @wasm/context-lost?))
              (perf/begin-measure "render-finish")
              (h/call wasm/internal-module "_set_view_end")
              (perf/end-measure "render-finish")
              ;; Use async _render: visible tiles render synchronously
              ;; (no yield), interest-area tiles render progressively
              ;; via rAF.  _set_view_end already rebuilt the tile
              ;; index.  For pan, most tiles are cached so the render
              ;; completes in the first frame.  For zoom, interest-
              ;; area tiles (~3 tile margin) don't block the main
              ;; thread.
              (h/call wasm/internal-module "_render" 0)))]
    (fns/debounce do-render DEBOUNCE_DELAY_MS)))

(defn set-view-box
  [zoom vbox]
  (perf/begin-measure "set-view-box")
  (h/call wasm/internal-module "_set_view_start")
  (h/call wasm/internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
  (perf/end-measure "set-view-box")

  (perf/begin-measure "render-from-cache")
  (h/call wasm/internal-module "_render_from_cache" 0)
  (render-finish)
  (perf/end-measure "render-from-cache"))

(defn- ensure-text-content
  "Guarantee that the shape always sends a valid text tree to WASM. When the
  content is nil (freshly created text) we fall back to
  tc/default-text-content so the renderer receives typography information."
  [content]
  (or content (tc/v2-default-text-content)))

(defn set-object
  [shape]
  (perf/begin-measure "set-object")
  (when shape
    (let [shape        (svg-filters/apply-svg-derived shape)
          id           (dm/get-prop shape :id)
          type         (dm/get-prop shape :type)

          masked       (get shape :masked-group)

          fills        (get shape :fills)
          strokes      (if (= type :group)
                         [] (get shape :strokes))
          children     (get shape :shapes)
          content      (let [content (get shape :content)]
                         (if (= type :text)
                           (ensure-text-content content)
                           content))
          bool-type    (get shape :bool-type)
          grow-type    (get shape :grow-type)
          blur         (get shape :blur)
          svg-attrs    (get shape :svg-attrs)
          shadows      (get shape :shadow)]

      (shapes/set-shape-base-props shape)

      ;; Remaining properties that need separate calls (variable-length or conditional)
      (set-shape-children children)
      (set-shape-blur blur)
      (when (= type :group)
        (set-masked (boolean masked)))
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
      (set-shape-shadows shadows)
      (when (= type :text)
        (set-shape-grow-type grow-type))

      (set-shape-layout shape)
      (set-layout-data shape)
      (let [is-text? (= type :text)
            pending_thumbnails (into [] (concat
                                         (when is-text? (set-shape-text-content id content))
                                         (when is-text? (set-shape-text-images id content true))
                                         (set-shape-fills id fills true)
                                         (set-shape-strokes id strokes true)))
            pending_full (into [] (concat
                                   (when is-text? (set-shape-text-images id content false))
                                   (set-shape-fills id fills false)
                                   (set-shape-strokes id strokes false)))]
        (perf/end-measure "set-object")
        {:thumbnails pending_thumbnails
         :full pending_full}))))

(defn- update-text-layouts
  "Synchronously update text layouts for all shapes and send rect updates
   to the worker index."
  [text-ids]
  (run! f/update-text-layout text-ids))

(defn process-pending
  [shapes thumbnails full on-complete]
  (let [pending-thumbnails
        (d/index-by :key :callback thumbnails)

        pending-full
        (d/index-by :key :callback full)]

    ;; Run text layouts synchronously so shapes are immediately correct.
    (let [text-ids (into [] (comp (filter cfh/text-shape?) (map :id)) shapes)]
      (when (seq text-ids)
        (update-text-layouts text-ids)))

    (if (or (seq pending-thumbnails) (seq pending-full))
      (->> (rx/concat
            (->> (rx/from (vals pending-thumbnails))
                 (rx/merge-map (fn [callback] (if (fn? callback) (callback) (rx/empty))))
                 (rx/reduce conj [])
                 (rx/catch #(rx/empty)))
            (->> (rx/from (vals pending-full))
                 (rx/mapcat (fn [callback] (if (fn? callback) (callback) (rx/empty))))
                 (rx/reduce conj [])
                 (rx/catch #(rx/empty))))
           (rx/subs!
            (fn [_]
              ;; Fonts are now loaded — recompute text layouts so Skia
              ;; uses the real metrics instead of fallback-font estimates.
              (let [text-ids (into [] (comp (filter cfh/text-shape?) (map :id)) shapes)]
                (when (seq text-ids)
                  (update-text-layouts text-ids)))
              (request-render "images-loaded"))
            noop-fn
            (fn [] (when (fn? on-complete) (on-complete)))))
      ;; No pending images — complete immediately.
      (when on-complete (on-complete)))))

(defn process-object
  [shape]
  (let [{:keys [thumbnails full]} (set-object shape)]
    (process-pending [shape] thumbnails full noop-fn)))

(defn- process-shapes-chunk
  "Process shapes starting at `start-index` until the time budget is exhausted.
   Returns {:thumbnails [...] :full [...] :next-index n}"
  [shapes start-index thumbnails-acc full-acc]
  (let [total    (count shapes)
        deadline (+ (js/performance.now) CHUNK_TIME_BUDGET_MS)]
    (loop [index start-index
           t-acc (transient thumbnails-acc)
           f-acc (transient full-acc)]
      (if (and (< index total)
               ;; Check performance.now every 8 shapes to reduce overhead
               (or (pos? (bit-and (- index start-index) 7))
                   (<= (js/performance.now) deadline)))
        (let [shape (nth shapes index)
              {:keys [thumbnails full]} (set-object shape)]
          (recur (inc index)
                 (reduce conj! t-acc thumbnails)
                 (reduce conj! f-acc full)))
        {:thumbnails (persistent! t-acc)
         :full (persistent! f-acc)
         :next-index index}))))

(defn- set-objects-async
  "Asynchronously process shapes in time-budgeted chunks, yielding to the
   browser between chunks so the UI stays responsive.
   Returns a promise that resolves when all shapes are processed."
  [shapes render-callback on-shapes-ready]
  (let [total-shapes (count shapes)]
    (p/create
     (fn [resolve _reject]
       (letfn [(process-next-chunk [index thumbnails-acc full-acc]
                 (if (< index total-shapes)
                   ;; Process one time-budgeted chunk
                   (let [{:keys [thumbnails full next-index]}
                         (process-shapes-chunk shapes index
                                               thumbnails-acc full-acc)]
                     ;; Yield to browser, then continue with next chunk
                     (-> (yield-to-browser)
                         (p/then (fn [_]
                                   (process-next-chunk next-index thumbnails full)))))
                   ;; All chunks done - finalize
                   (do
                     (perf/end-measure "set-objects")

                     ;; Notify that shapes are loaded and tiles rebuilt
                     (when on-shapes-ready (on-shapes-ready))
                     ;; Show shapes immediately: end loading overlay + unblock rendering
                     (h/call wasm/internal-module "_end_loading")
                     (end-shapes-loading!)

                     ;; Rebuild the tile index so _render knows which shapes
                     ;; map to which tiles after a page switch.
                     (h/call wasm/internal-module "_set_view_end")

                     ;; Text layouts must run after _end_loading (they
                     ;; depend on state that is only correct when loading
                     ;; is false).  Each call touch_shape → touched_ids.
                     (let [text-ids (into [] (comp (filter cfh/text-shape?) (map :id)) shapes)]
                       (when (seq text-ids)
                         (update-text-layouts text-ids)))
                     (if render-callback
                       (render-callback)
                       (request-render "set-objects-complete"))
                     (ug/dispatch! (ug/event "penpot:wasm:set-objects"))
                     (resolve nil)

                     ;; Kick off image fetches in the background.
                     ;; The promise is already resolved so these don't
                     ;; block the caller.
                     (let [pending-thumbnails (d/index-by :key :callback thumbnails-acc)
                           pending-full       (d/index-by :key :callback full-acc)]
                       (when (or (seq pending-thumbnails) (seq pending-full))
                         (->> (rx/concat
                               (->> (rx/from (vals pending-thumbnails))
                                    (rx/merge-map
                                     (fn [callback]
                                       (if (fn? callback) (callback) (rx/empty))))
                                    (rx/reduce conj []))
                               (->> (rx/from (vals pending-full))
                                    (rx/mapcat
                                     (fn [callback]
                                       (if (fn? callback) (callback) (rx/empty))))
                                    (rx/reduce conj [])))
                              (rx/subs!
                               (fn [_]
                                 (let [text-ids (into [] (comp (filter cfh/text-shape?) (map :id)) shapes)]
                                   (when (seq text-ids)
                                     (update-text-layouts text-ids)))
                                 (request-render "images-loaded"))
                               noop-fn
                               noop-fn)))))))]
         (process-next-chunk 0 [] []))))))

(defn- set-objects-sync
  "Synchronously process all shapes (for small shape counts)."
  [shapes render-callback on-shapes-ready]
  (let [total-shapes (count shapes)
        {:keys [thumbnails full]}
        (loop [index 0 thumbnails-acc (transient []) full-acc (transient [])]
          (if (< index total-shapes)
            (let [shape (nth shapes index)
                  {:keys [thumbnails full]} (set-object shape)]
              (recur (inc index)
                     (reduce conj! thumbnails-acc thumbnails)
                     (reduce conj! full-acc full)))
            {:thumbnails (persistent! thumbnails-acc) :full (persistent! full-acc)}))]
    (perf/end-measure "set-objects")
    (when on-shapes-ready (on-shapes-ready))
    ;; Rebuild the tile index so _render knows which shapes
    ;; map to which tiles after a page switch.
    (h/call wasm/internal-module "_set_view_end")
    (process-pending shapes thumbnails full
                     (fn []
                       (if render-callback
                         (render-callback)
                         (request-render "set-objects-sync-complete"))
                       (ug/dispatch! (ug/event "penpot:wasm:set-objects"))))))

(defn- shapes-in-tree-order
  "Returns shapes sorted in tree order (parents before children).
   This ensures parent shapes are processed before their children,
   maintaining proper shape reference consistency in WASM."
  [objects]
  ;; Get IDs in tree order starting from root (uuid/zero)
  ;; If root doesn't exist (e.g., filtered thumbnail data), fall back to
  ;; finding top-level shapes (those without a parent in objects) and
  ;; traversing from there.
  (if (contains? objects uuid/zero)
    ;; Normal case: traverse from root
    (let [ordered-ids (cfh/get-children-ids-with-self objects uuid/zero)]
      (into []
            (keep #(get objects %))
            ordered-ids))
    ;; Fallback for filtered data (thumbnails): find top-level shapes and traverse
    (let [;; Find shapes whose parent is not in the objects map (top-level in this subset)
          top-level-ids (->> (vals objects)
                             (filter (fn [shape]
                                       (not (contains? objects (:parent-id shape)))))
                             (map :id))
          ;; Get all children in order for each top-level shape
          all-ordered-ids (into []
                                (mapcat #(cfh/get-children-ids-with-self objects %))
                                top-level-ids)]
      (into []
            (keep #(get objects %))
            all-ordered-ids))))

(defn set-objects
  "Set all shape objects for rendering.

   Shapes are processed in tree order (parents before children)
   to maintain proper shape reference consistency in WASM.

   on-shapes-ready is an optional callback invoked right after shapes are
   loaded into WASM (and tiles rebuilt for async). It fires before image
   loading begins, allowing callers to reveal the page content during
   transitions."
  ([objects]
   (set-objects objects nil nil false))
  ([objects render-callback]
   (set-objects objects render-callback nil false))
  ([objects render-callback on-shapes-ready force-sync]
   (perf/begin-measure "set-objects")
   (let [shapes (shapes-in-tree-order objects)
         total-shapes (count shapes)]
     (if (or force-sync (< total-shapes ASYNC_THRESHOLD))
       (set-objects-sync shapes render-callback on-shapes-ready)
       (do
         (begin-shapes-loading!)
         (h/call wasm/internal-module "_begin_loading")
         ;; NOTE: to render a loading overlay in the future
         ;;  (when-not on-shapes-ready
         ;;    (h/call wasm/internal-module "_render_loading_overlay"))
         (try
           (-> (set-objects-async shapes render-callback on-shapes-ready)
               (p/catch (fn [error]
                          (h/call wasm/internal-module "_end_loading")
                          (end-shapes-loading!)
                          (js/console.error "Async WASM shape loading failed" error))))
           (catch :default error
             (h/call wasm/internal-module "_end_loading")
             (end-shapes-loading!)
             (js/console.error "Async WASM shape loading failed" error)
             (throw error)))
         nil)))))

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
          size    (mem/get-alloc-size entries INPUT-MODIFIER-U8-SIZE)
          offset  (mem/alloc->offset-32 size)]

      (reduce (fn [offset [id data]]
                (let [transform (:transform data)
                      kind (:kind data)]
                  (-> offset
                      (mem.h32/write-uuid heapu32 id)
                      (mem.h32/write-matrix heapf32 transform)
                      (mem.h32/write-u32 heapu32 (sr/translate-transform-entry-kind kind)))))
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

(defn set-modifiers-start
  "Enter interactive transform mode (drag / resize / rotate). Enables
   fast-mode effect skipping in the renderer and activates an atlas
   backdrop so tiles do not appear sequentially or flicker while the
   gesture is in progress."
  []
  (when (and wasm/context-initialized? (not @wasm/context-lost?))
    (h/call wasm/internal-module "_set_modifiers_start")))

(defn set-modifiers-end
  "Leave interactive transform mode. Cancels any pending async render
   scheduled under it; the caller is expected to trigger a full-quality
   render (via `request-render`) once the gesture is committed."
  []
  (when (and wasm/context-initialized? (not @wasm/context-lost?))
    (h/call wasm/internal-module "_set_modifiers_end")))

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
  [base-objects zoom vbox &
   {:keys [background background-opacity on-render on-shapes-ready force-sync]
    :or {background-opacity 1}}]
  (let [rgba (when background (sr-clr/hex->u32argb background background-opacity))
        total-shapes (count (vals base-objects))]

    (when rgba (h/call wasm/internal-module "_set_canvas_background" rgba))
    (h/call wasm/internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
    (h/call wasm/internal-module "_init_shapes_pool" total-shapes)
    (set-objects base-objects on-render on-shapes-ready force-sync)))

(defn- run-resource-callbacks!
  [entries]
  (if (seq entries)
    (p/create
     (fn [resolve _reject]
       (->> (rx/from (vals (d/index-by :key :callback entries)))
            (rx/merge-map (fn [callback] (if (fn? callback) (callback) (rx/empty))))
            (rx/reduce conj [])
            (rx/subs! (fn [_] (resolve nil))
                      (fn [_cause] (resolve nil))
                      (fn [] (resolve nil))))))
    (p/resolved nil)))

(defn- replay-font-resources!
  [fonts]
  (let [pending (into [] (f/store-fonts fonts))]
    (run-resource-callbacks! pending)))

(defn- derive-font-resources
  [base-objects payload-fonts]
  (let [object-fonts
        (->> (vals base-objects)
             (filter cfh/text-shape?)
             (mapcat (fn [shape]
                       (let [content (ensure-text-content (:content shape))
                             direct-fonts (f/get-content-fonts content)
                             ;; `true` would call `write-shape-text`, which requires
                             ;; an active current shape in WASM and can panic during
                             ;; reload pre-processing. We only need fallback font
                             ;; discovery here, so use side-effect free mode.
                             fallback-fonts (fonts-from-text-content content false)]
                         (concat direct-fonts fallback-fonts))))
             (into #{}))]
    (into [] (set (concat payload-fonts object-fonts)))))

(defn- replay-image-resources!
  [image-resources]
  (let [pending
        (into []
              (keep (fn [{:keys [shape-id image-id thumbnail?]}]
                      (when (and (uuid? image-id) (or (nil? shape-id) (uuid? shape-id)))
                        (fetch-image (or shape-id uuid/zero) image-id (boolean thumbnail?)))))
              image-resources)]
    (run-resource-callbacks! pending)))

(defn- wait-next-frame!
  []
  (p/create
   (fn [resolve _reject]
     (js/requestAnimationFrame (fn [] (resolve nil))))))

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
    (bit-or 2r00000000000000000000000000000001)
    (text-editor-wasm?)
    (bit-or 2r00000000000000000000000000000100)
    (contains? cf/flags :render-wasm-info)
    (bit-or 2r00000000000000000000000000001000)))

(defn- wasm-aa-threshold-from-route-params
  "Reads optional `aa_threshold` query param from the router"
  []
  (when-let [raw (let [p (rt/get-params @st/state)]
                   (:aa_threshold p))]
    (let [n (if (string? raw) (js/parseFloat raw) raw)]
      (when (and (number? n) (not (js/isNaN n)) (pos? n))
        n))))

(defn- wasm-blur-downscale-threshold-from-route-params
  "Reads optional `aa_threshold` query param from the router"
  []
  (when-let [raw (let [p (rt/get-params @st/state)]
                   (:blur_downscale_threshold p))]
    (let [n (if (string? raw) (js/parseFloat raw) raw)]
      (when (and (number? n) (not (js/isNaN n)) (pos? n))
        n))))

(defn- wasm-max-blocking-time-ms-from-route-params
  "Reads optional `aa_threshold` query param from the router"
  []
  (when-let [raw (let [p (rt/get-params @st/state)]
                   (:max_blocking_time_ms p))]
    (let [n (if (string? raw) (js/parseInt raw 10) raw)]
      (when (and (number? n) (not (js/isNaN n)) (pos? n))
        n))))

(defn- wasm-node-batch-threshold-from-route-params
  "Reads optional `aa_threshold` query param from the router"
  []
  (when-let [raw (let [p (rt/get-params @st/state)]
                   (:node_batch_threshold p))]
    (let [n (if (string? raw) (js/parseInt raw 10) raw)]
      (when (and (number? n) (not (js/isNaN n)) (pos? n))
        n))))

(defn- wasm-viewport-interest-area-threshold-from-route-params
  "Reads optional `aa_threshold` query param from the router"
  []
  (when-let [raw (let [p (rt/get-params @st/state)]
                   (:viewport_interest_area_threshold p))]
    (let [n (if (string? raw) (js/parseInt raw 10) raw)]
      (when (and (number? n) (not (js/isNaN n)) (pos? n))
        n))))

(defn set-canvas-size
  [canvas]
  (let [width (or (.-clientWidth ^js canvas) (.-width ^js canvas))
        height (or (.-clientHeight ^js canvas) (.-height ^js canvas))]
    (set! (.-width canvas) (* dpr width))
    (set! (.-height canvas) (* dpr height))))

(defn- on-webgl-context-lost
  [event]
  (dom/prevent-default event)
  (reset! wasm/context-lost? true)
  (st/async-emit!
   (ntf/show {:content (tr "webgl.webgl-context-lost.toast")
              :type :toast
              :level :error
              :timeout 5000}))
  (st/emit! (drw/context-lost)))

(defn- on-webgl-context-restored
  [event]
  (dom/prevent-default event)
  (reset! wasm/context-lost? false)
  (st/emit! (drw/context-restored))
  (when-let [payload @last-reload-payload*]
    (-> (reload-renderer! (assoc payload :canvas (or (:canvas payload) wasm/canvas)))
        (p/then (fn [_]
                  (st/async-emit!
                   (ntf/show {:content (tr "webgl.webgl-context-recovered.toast")
                              :type :toast
                              :level :success
                              :timeout 3000}))))
        (p/catch (fn [cause]
                   (log/error :hint "wasm reload after context restore failed"
                              :cause cause)
                   nil)))))

(defn init-canvas-context
  [canvas]
  (if-not (wasm/module-ready?)
    false
    (let [gl      (unchecked-get wasm/internal-module "GL")
          flags   (debug-flags)
          context-id (if (dbg/enabled? :wasm-gl-context-init-error) "fail" "webgl2")
          context (.getContext ^js canvas context-id default-context-options)
          context-init? (not (nil? context))
          browser (sr/translate-browser cf/browser)]
      (when-not (nil? context)
        (let [handle (.registerContext ^js gl context #js {"majorVersion" 2})]
          (.makeContextCurrent ^js gl handle)
          (set! wasm/gl-context-handle handle)
          (set! wasm/gl-context context)

          ;; Force the WEBGL_debug_renderer_info extension as emscripten does not enable it
          (.getExtension context "WEBGL_debug_renderer_info")

          ;; Initialize Wasm Render Engine
          (h/call wasm/internal-module "_init" (/ (.-width ^js canvas) dpr) (/ (.-height ^js canvas) dpr))
          (h/call wasm/internal-module "_set_render_options" flags dpr)
          (when-let [t (wasm-aa-threshold-from-route-params)]
            (h/call wasm/internal-module "_set_antialias_threshold" t))
          (when-let [t (wasm-viewport-interest-area-threshold-from-route-params)]
            (h/call wasm/internal-module "_set_viewport_interest_area_threshold" t))
          (when-let [t (wasm-max-blocking-time-ms-from-route-params)]
            (h/call wasm/internal-module "_set_max_blocking_time_ms" t))
          (when-let [t (wasm-node-batch-threshold-from-route-params)]
            (h/call wasm/internal-module "_set_node_batch_threshold" t))
          (when-let [t (wasm-blur-downscale-threshold-from-route-params)]
            (h/call wasm/internal-module "_set_blur_downscale_threshold" t))
          (when-let [max-tex (webgl/max-texture-size context)]
            (h/call wasm/internal-module "_set_max_atlas_texture_size" max-tex))

          ;; Set browser and canvas size only after initialization
          (h/call wasm/internal-module "_set_browser" browser)
          (set-canvas-size canvas)

          ;; Add event listeners for WebGL context lost
          (set! wasm/canvas canvas)
          (.addEventListener canvas "webglcontextlost" on-webgl-context-lost)
          (.addEventListener canvas "webglcontextrestored" on-webgl-context-restored)
          (reset! wasm/context-lost? false)
          (set! wasm/context-initialized? true)))

      context-init?)))

(defn clear-canvas
  ([]
   (clear-canvas {}))
  ([{:keys [lose-browser-context?]
     :or {lose-browser-context? true}}]
   (try
     (set! wasm/context-initialized? false)

     ;; Cancel any pending animation frame to prevent race conditions.
     (when wasm/internal-frame-id
       (js/cancelAnimationFrame wasm/internal-frame-id))

     ;; Reset render flags to prevent new renders from being scheduled.
     (reset! pending-render false)
     (reset! shapes-loading? false)
     (reset! deferred-render? false)

     ;; Remove listener before losing/deleting context.
     (when wasm/canvas
       (.removeEventListener wasm/canvas "webglcontextlost" on-webgl-context-lost)
       (.removeEventListener wasm/canvas "webglcontextrestored" on-webgl-context-restored))

     (when (wasm/module-ready?)
       (free-gpu-resources)
       (h/call wasm/internal-module "_clean_up"))

     ;; Ensure the WebGL context is properly disposed so browsers do not keep
     ;; accumulating active contexts between page switches.
     (when-let [gl (unchecked-get wasm/internal-module "GL")]
       (when-let [handle wasm/gl-context-handle]
         (try
           ;; For hard teardown we can explicitly lose browser context.
           ;; For reload->reinit flows we skip this because immediate context
           ;; recreation may fail on some browsers/GPUs while context is lost.
           (when lose-browser-context?
             (when-let [ctx wasm/gl-context]
               (when-let [lose-ext (.getExtension ^js ctx "WEBGL_lose_context")]
                 (.loseContext ^js lose-ext))))
           (.deleteContext ^js gl handle)
           (catch :default dispose-error
             (.error js/console dispose-error)))))

     (wasm-gesture/reset-after-wasm-reload!)
     (wasm/reset-context-state!)
     true

     ;; If this panics we don't want to crash. This happens sometimes with
     ;; hot-reload in development.
     (catch :default error
       (.error js/console error)
       (wasm-gesture/reset-after-wasm-reload!)
       (wasm/reset-context-state!)
       false))))

(defn reload-renderer!
  [{:keys [canvas
           base-objects
           zoom
           vbox
           fonts
           image-resources
           background
           background-opacity
           on-render
           on-shapes-ready
           force-sync]
    :or {fonts []
         image-resources []
         background-opacity 1
         force-sync false}
    :as payload}]
  (ug/dispatch! (ug/event "penpot:wasm:reload-start"))
  (let [fonts (derive-font-resources base-objects fonts)]
    (-> (p/resolved nil)
        ;; Keep teardown strict (`_clean_up` + deleteContext) but do not
        ;; force `loseContext` because we immediately create a new context.
        (p/then (fn [_]
                  (let [was-cleared? (clear-canvas {:lose-browser-context? false})]
                    (when-not was-cleared?
                      (ex/raise :type :wasm-error
                                :code :wasm-reload-context-failure
                                :hint "WASM renderer cleanup failed")))))
        ;; Give browser a frame to settle context deletion before init.
        (p/then (fn [_] (wait-next-frame!)))
        (p/then (fn [_]
                  (let [context-ready? (init-canvas-context canvas)]
                    (when-not context-ready?
                      (ex/raise :type :wasm-error
                                :code :wasm-reload-context-failure
                                :hint "WASM renderer could not create a new WebGL context"))
                    ;; Gesture bookkeeping (`modifiers.cljs`) uses compare-and-set on an atom
                    ;; that survives WASM teardown; reset so it matches fresh `_init` state.
                    (wasm-gesture/reset-after-wasm-reload!))))
        ;; Ensure render surfaces are blank before replay to avoid overpainting.
        (p/then (fn [_] (h/call wasm/internal-module "_reset_canvas")))
        (p/then (fn [_] (replay-font-resources! fonts)))
        (p/then (fn [_] (replay-image-resources! image-resources)))
        (p/then
         (fn []
           (initialize-viewport base-objects zoom vbox
                                :background background
                                :background-opacity background-opacity
                                :on-render on-render
                                :on-shapes-ready on-shapes-ready
                                :force-sync force-sync)
           (request-render "reload-renderer")
           (ug/dispatch! (ug/event "penpot:wasm:reload-complete"))
           payload))
        (p/catch
         (fn [cause]
           (ug/dispatch! (ug/event "penpot:wasm:reload-failed"))
           (clear-canvas)
           (p/rejected cause))))))

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
  (try
    (let [offset (-> (h/call wasm/internal-module "_current_to_path")
                     (mem/->offset-32))
          heap   (mem/get-heap-u32)
          length (aget heap offset)
          data   (mem/slice heap
                            (+ offset 1)
                            (* length path.impl/SEGMENT-U32-SIZE))
          content (path/from-bytes data)]
      (mem/free)
      content)
    (catch :default cause
      (mem/free)
      (throw cause))))

(defn stroke-to-path
  "Converts a shape's stroke at the given index into a filled path.
   Returns the stroke outline as PathData content."
  [id stroke-index]
  (use-shape id)
  (try
    (let [offset (-> (h/call wasm/internal-module "_convert_stroke_to_path" stroke-index)
                     (mem/->offset-32))
          heap   (mem/get-heap-u32)
          length (aget heap offset)]
      (if (pos? length)
        (let [data    (mem/slice heap
                                 (+ offset 1)
                                 (* length path.impl/SEGMENT-U32-SIZE))
              content (path/from-bytes data)]
          (mem/free)
          content)
        (do (mem/free)
            nil)))
    (catch :default cause
      (mem/free)
      (throw cause))))

(defn calculate-bool*
  [bool-type ids]
  (let [size   (mem/get-alloc-size ids UUID-U8-SIZE)
        heap   (mem/get-heap-u32)
        offset (mem/alloc->offset-32 size)]

    (reduce (fn [offset id]
              (mem.h32/write-uuid offset heap id))
            offset
            (rseq ids))

    (try
      (let [offset
            (-> (h/call wasm/internal-module "_calculate_bool" (sr/translate-bool-type bool-type))
                (mem/->offset-32))

            length  (aget heap offset)
            data    (mem/slice heap
                               (+ offset 1)
                               (* length path.impl/SEGMENT-U32-SIZE))
            content (path/from-bytes data)]
        (mem/free)
        content)
      (catch :default cause
        (mem/free)
        (throw cause)))))

(defn calculate-bool
  [shape objects]

  ;; We need to be able to calculate the boolean data but we cannot
  ;; depend on the serialization flow.
  ;; start_temp_object / end_temp_object create a new shapes_pool
  ;; temporary and then we serialize the objects needed to calculate the
  ;; boolean object.
  ;; After the content is returned we discard that temporary context
  (h/call wasm/internal-module "_start_temp_objects")

  (let [bool-type (get shape :bool-type)
        ids (get shape :shapes)
        all-children
        (->> ids
             (mapcat #(cfh/get-children-with-self objects %)))]

    (h/call wasm/internal-module "_init_shapes_pool" (count all-children))
    (run! set-object all-children)

    (let [content (-> (calculate-bool* bool-type ids)
                      (path.impl/path-data))]
      (h/call wasm/internal-module "_end_temp_objects")
      content)))

(def POSITION-DATA-U8-SIZE 36)
(def POSITION-DATA-U32-SIZE (/ POSITION-DATA-U8-SIZE 4))

(defn calculate-position-data
  [shape]
  (when wasm/context-initialized?
    (use-shape (:id shape))
    (let [heapf32 (mem/get-heap-f32)
          heapu32 (mem/get-heap-u32)
          offset (-> (h/call wasm/internal-module "_calculate_position_data")
                     (mem/->offset-32))
          length (aget heapu32 offset)

          max-offset (+ offset 1 (* length POSITION-DATA-U32-SIZE))

          result
          (loop [result (transient [])
                 offset (inc offset)]
            (if (< offset max-offset)
              (let [entry (dr/read-position-data-entry heapu32 heapf32 offset)]
                (recur (conj! result entry)
                       (+ offset POSITION-DATA-U32-SIZE)))
              (persistent! result)))

          content (:content shape)]

      (mem/free)

      (into []
            (keep
             (fn [{:keys [paragraph span start-pos end-pos direction x y width height]}]
               (let [element (-> content :children
                                 (get 0) :children ;; paragraph-set
                                 (get paragraph) :children ;; paragraph
                                 (get span))
                     element-text (:text element)]

                 ;; Add comprehensive nil-safety checks
                 ;; Be aware that for RTL texts `start-pos` can be greatert han `end-pos`
                 (when (and element element-text)
                   (let [text (subs element-text start-pos end-pos)]
                     (d/patch-object
                      txt/default-text-attrs
                      (d/without-nils
                       {:x x
                        :y (+ y height)
                        :width width
                        :height height
                        :direction       (dr/translate-direction direction)
                        :font-id         (get element :font-id)
                        :font-family     (get element :font-family)
                        :font-size       (dm/str (get element :font-size) "px")
                        :font-weight     (get element :font-weight)
                        :text-transform  (get element :text-transform)
                        :text-decoration (get element :text-decoration)
                        :letter-spacing  (dm/str (get element :letter-spacing) "px")
                        :font-style      (get element :font-style)
                        :fills           (get element :fills)
                        :text            text})))))))
            result))))

(defn apply-canvas-blur
  []
  (let [already? @page-transition?
        epoch    (begin-page-transition!)]
    (set-transition-tiles-complete-handler! epoch end-page-transition!)
    ;; Two-phase transition:
    ;; - Apply CSS blur to the live canvas immediately (no async wait), so the user
    ;;   sees the transition right away.
    ;; - In parallel, capture a `blob:` snapshot URL; once ready, switch the overlay
    ;;   to that fixed image (and guard with `epoch` to avoid stale async updates).
    (set-transition-blur!)
    ;; Lock the snapshot for the whole transition: if the user clicks to another page
    ;; while the transition is active, keep showing the original page snapshot until
    ;; the final target page finishes rendering.
    (if already?
      (p/resolved nil)
      (do
        ;; If we already have a snapshot URL, use it immediately.
        (when-let [url wasm/canvas-snapshot-url]
          (when (string? url)
            (reset! transition-image-url* url)))

        ;; Capture a fresh snapshot asynchronously and update the overlay as soon
        ;; as it is ready (guarded by `epoch` to avoid stale async updates).
        (-> (capture-canvas-snapshot-url)
            (p/then (fn [url]
                      (when (and (string? url)
                                 @page-transition?
                                 (= epoch @transition-epoch*))
                        (reset! transition-image-url* url))
                      url))
            (p/catch (fn [_] nil)))))))

(defn render-shape-pixels
  [shape-id scale]
  (let [buffer (uuid/get-u32 shape-id)

        offset
        (h/call wasm/internal-module "_render_shape_pixels"
                (aget buffer 0)
                (aget buffer 1)
                (aget buffer 2)
                (aget buffer 3)
                scale)

        heap (mem/get-heap-u8)
        heapu32 (mem/get-heap-u32)
        length (aget heapu32 (mem/->offset-32 offset))
        result (dr/read-image-bytes heap (+ offset 12) length)]
    (mem/free)
    result))

(defn init-wasm-module
  [module]
  (let [default-fn (unchecked-get module "default")
        href       (cf/resolve-href "js/render-wasm.wasm")]
    (default-fn #js {:locateFile (constantly href)})))

(defonce module
  (delay
    (if (exists? js/dynamicImport)
      (let [uri (cf/resolve-href "js/render-wasm.js")]
        (->> (mod/import uri)
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


