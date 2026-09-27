;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.browser
  "Browser bridge for the benchmarks.

  Lifecycle here mirrors the editor cold-load path
  (`app.render-wasm.api/initialize-viewport` + `set-objects` sync).

  This ns exports `bridge`, which is what the Node 'pilot' uses inside the page.
  We only ever pass data through `page.evaluate`.

  All `bridge` entries resolve, that is they never return an unhandled rejected
  promise.

  Failures are returned as `{\"status\" \"failed\" ...}` maps."
  (:require
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.schema :as sm]
   ;; bind the enum table from the prepared renderer build's `shared.js` so
   ;; a missing `shared.js` fails the compile. A stale or mismatched one throws
   ;; at load
   [app.render-wasm.api.enums]
   [app.render-wasm.api.webgl :as webgl]
   [benches.render-wasm.scenes.common :as scenes]
   [benches.render-wasm.scenes.core :as core]
   [benches.render-wasm.scenes.rects :as rects]
   [benches.render-wasm.upload :as upload]))

;; TODO(mem:render-wasm/performance/cljs-rewrite/06-render-and-interaction-protocol):
;; move these to the shared WASM layer and delete them here. They mirror
;; `app.render-wasm.api/FRAME_TYPE_*` until ticket 06 owns the protocol.
(def ^:private frame-full 2)

(def ^:private default-view
  {:scale 1 :x 0 :y 0})

(def ^:private poll-frame-budget
  "Own deadline for the interim Full poll: at most this many rAF frames."
  600)

(def ^:private poll-ms-budget
  "Own deadline for the interim Full poll: at most this many wall ms."
  15000)

(def schema:load-args
  "Bridge-boundary schema for `load-rects`. Invalid args produce a failure
  map before any timer starts, never inside timed bodies."
  [:map {:closed true}
   [:seed [:int {:min 0 :max 4294967295}]]
   [:view {:optional true} core/schema:view]
   [:module-url {:optional true} string?]
   [:wasm-url {:optional true} string?]])

;; Monotonic owner generation. Every async continuation compares its
;; captured epoch and drops stale results silently; dispose/load bump it.
(defonce ^:private owner-epoch*
  (atom 0))

;; Cached Emscripten import. Import precedes module-init timing (untimed).
(defonce ^:private module-promise*
  (atom nil))

;; Page-scoped canvas owned by the current load. Fresh page per attempt in
;; the future runner; the pilot loads once.
(defonce ^:private canvas*
  (atom nil))

(defn- fail-data
  [m]
  (clj->js (assoc m "status" "failed")))

(defn- mark-live!
  []
  (reset! wasm/context-lost? false)
  (set! wasm/context-initialized? true))

(defn- now
  []
  (.now js/performance))

(defn- ensure-module!
  "Imports the Emscripten glue (cached per page, untimed: script import
  precedes module-init timing) and instantiates it with `locateFile`
  pointing at the prepared `.wasm` bytes. Resolves
  `{:module instance :factory-ms ms}` where factory-ms covers the
  instantiate call only."
  [module-url wasm-url]
  (when (nil? @module-promise*)
    (reset! module-promise*
            (-> (js/dynamicImport module-url)
                (.then (fn [module]
                         (let [t0         (now)
                               default-fn (unchecked-get module "default")]
                           (-> (default-fn #js {:locateFile (constantly wasm-url)})
                               (.then (fn [instance]
                                        {:module instance
                                         :factory-ms (- (now) t0)})))))))))
  @module-promise*)

(defn- renderer-string
  "Reads the unmasked renderer (SwiftShader/llvmpipe detection for the
  software-renderer warning). The init helper forces the debug extension."
  [context]
  (try
    (let [ext (.getExtension ^js context "WEBGL_debug_renderer_info")]
      (if (some? ext)
        (str (.getParameter ^js context (unchecked-get ext "UNMASKED_RENDERER_WEBGL")))
        "unknown"))
    (catch :default _ "unknown")))

(defn- software-renderer?
  [s]
  (boolean (re-find #"(?i)swiftshader|llvmpipe|software|basic render" (or s ""))))

(defn- poll-full
  "TODO(mem:render-wasm/performance/cljs-rewrite/06-render-and-interaction-protocol):
  interim inline drain. Ticket 06 replaces this with `protocol.cljs`; do not
  extend it. Resolves `{:ms [...] :frames n}` at Full, `{:full false ...}` on
  budget exhaustion, and checks the owner epoch every frame. A synchronous
  throw inside a frame rejects: rAF callbacks run outside the executor, so
  without this the bridge promise would hang."
  [epoch started-frames started-ms]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [frames]
               (if (not= epoch @owner-epoch*)
                 (resolve #js {"stale" true})
                 (let [frame-type (try
                                    (h/call wasm/internal-module "_render" (now) 0)
                                    (catch :default cause
                                      (reject cause)
                                      ::thrown))]
                   (when-not (= frame-type ::thrown)
                     (cond
                       (= frame-type frame-full)
                       (resolve #js {"full" true
                                     "frames" (- frames started-frames)
                                     "ms" (- (now) started-ms)})

                       (or (>= frames (+ started-frames poll-frame-budget))
                           (>= (- (now) started-ms) poll-ms-budget))
                       (resolve #js {"full" false
                                     "frames" (- frames started-frames)
                                     "ms" (- (now) started-ms)
                                     "frameType" frame-type})

                       :else
                       (.requestAnimationFrame
                        js/window
                        (fn [] (step (inc frames)))))))))]
       (.requestAnimationFrame js/window (fn [] (step (inc started-frames))))))))

(defn- read-graphics
  "Records effective graphics settings from the declared helper authority
  plus the observed renderer string."
  [width height dpr renderer]
  (let [opts webgl/default-context-options]
    {"width" width
     "height" height
     "dpr" dpr
     "webgl2" true
     "antialias" (unchecked-get opts "antialias")
     "depth" (unchecked-get opts "depth")
     "stencil" (unchecked-get opts "stencil")
     "alpha" (unchecked-get opts "alpha")
     "preserveDrawingBuffer" (unchecked-get opts "preserveDrawingBuffer")
     "renderer" renderer
     "software" (software-renderer? renderer)}))

(defn- render-ok
  [module-ms graphics-ms upload-ms alive? poll snapshot seed width height dpr renderer]
  (let [pm (js->clj poll :keywordize-keys true)]
    (cond
      (:stale pm)
      ;; dispose! resets loss state, so stale-plus-lost means genuine
      ;; context loss, not disposal.
      (if @wasm/context-lost?
        (fail-data {:phase "context-lost"
                    :message "WebGL context lost during render"})
        #js {"status" "stale"})

      ;; Safety net: every loss writer bumps the epoch, so staleness
      ;; normally fires first.
      (not alive?)
      (fail-data {:phase "first-render"
                  :message "disposed or context lost during render"})

      (:full pm)
      (clj->js
       {"status" "ok"
        "moduleInitMs" module-ms
        "graphicsInitMs" graphics-ms
        "uploadMs" upload-ms
        "firstRenderMs" (:ms pm)
        "renderFrames" (:frames pm)
        "scene" {"shapes" (count (:objects snapshot))
                 "seed" seed}
        "effectiveGraphics" (read-graphics width height dpr renderer)})

      :else
      (fail-data {:phase "first-render"
                  :message "Full not reached in budget"
                  :detail pm}))))

(defn- install-listeners!
  "Wires context lost/restored handling for `canvas`, epoch-guarded: a stale
  canvas (removed but not yet GC'd) must not kill the live owner."
  [canvas epoch]
  (.addEventListener
   canvas "webglcontextlost"
   (let [installed epoch]
     (fn [e]
       (.preventDefault ^js e)
       (when (= installed @owner-epoch*)
         (reset! wasm/context-lost? true)
         (swap! owner-epoch* inc)))))
  (.addEventListener
   canvas "webglcontextrestored"
   (let [installed epoch]
     (fn [_]
       (when (= installed @owner-epoch*)
         (swap! owner-epoch* inc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public interface
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dispose!
  "Closes the current owner: bumps the epoch (in-flight continuations go
  stale), tears down WASM context state and drops the module handle so a
  later load re-imports cleanly. Idempotent."
  []
  (swap! owner-epoch* inc)
  (wasm/reset-context-state!)
  ;; reset-context-state! deliberately preserves loss state for the editor
  ;; reload path; bench disposal owns it explicitly.
  (reset! wasm/context-lost? false)
  (set! wasm/internal-module nil)
  (reset! module-promise* nil)
  (when-let [canvas @canvas*]
    (.remove ^js canvas)
    (reset! canvas* nil))
  #js {"status" "disposed"})

;; TODO: remove in Ticket 06
(defn load-rects
  "Loads the `:rects/load` scene and renders it to Full. Takes plain data:
  `{\"seed\" int, \"view\" {scale x y viewport?}?, \"module-url\"?, \"wasm-url\"?}`.
  Viewport defaults to 1920x1080@2. Always resolves (failure maps included)."
  [args]
  (try
    (let [params (js->clj (or args #js {}) :keywordize-keys true)
          view   (merge default-view (:view params))
          seed   (:seed params)]
      (if-not (sm/validate schema:load-args
                           (cond-> {:seed seed}
                             (:view params) (assoc :view (:view params))
                             (:module-url params) (assoc :module-url (:module-url params))
                             (:wasm-url params) (assoc :wasm-url (:wasm-url params))))
        (js/Promise.resolve
         (fail-data {:phase "invalid-args"
                     :message "load-rects needs {seed int, view?}"}))
        (let [epoch (swap! owner-epoch* inc)]
          ;; Supersede: drop the previous owner's canvas now; its
          ;; continuations go stale through the epoch bump above.
          ;; The old GL context frees with the removed canvas.
          (when-let [old @canvas*]
            (.remove ^js old))
          (let [viewport (core/resolve-viewport view)
                width    (:width viewport)
                height   (:height viewport)
                dpr      (:dpr viewport)
                ;; Epoch-only until mark-live!: liveness requires an
                ;; initialized context, which does not exist yet.
                current? (fn [] (= epoch @owner-epoch*))
                live?    (fn [] (and (= epoch @owner-epoch*) (wasm/live?)))]
            (-> (ensure-module! (or (:module-url params) "./render-wasm.js")
                                (or (:wasm-url params) "./render-wasm.wasm"))
                (.then
                 (fn [{:keys [module factory-ms]}]
                   (when-not (current?)
                     (throw (ex-info "stale" {})))
                   (set! wasm/internal-module module)
                   (let [module-ms factory-ms
                         canvas    (doto (.createElement js/document "canvas")
                                     (aset "width" (* width dpr))
                                     (aset "height" (* height dpr)))
                         _         (.appendChild (.-body js/document) canvas)
                         _         (reset! canvas* canvas)
                         g0        (now)
                         ctx       (webgl/init-context!
                                    canvas
                                    {:module module
                                     :context-id "webgl2"
                                     :css-width width
                                     :css-height height
                                     :dpr dpr
                                     :flags 0
                                     :browser (sr/translate-browser :chrome)
                                     :params {}})]
                     (if (nil? ctx)
                       (do
                         (dispose!)
                         (fail-data {:phase "graphics-init"
                                     :message "WebGL2 context unavailable"}))
                       (do
                         (set! wasm/gl-context-handle (:handle ctx))
                         (set! wasm/gl-context (:context ctx))
                         (set! wasm/canvas canvas)
                         (mark-live!)
                         ;; Mirror the editor DOM sizing choice.
                         (h/call wasm/internal-module "_resize_viewbox" width height)
                         (install-listeners! canvas epoch)
                         (let [graphics-ms (- (now) g0)
                               renderer    (renderer-string (:context ctx))
                               ;; Generation, validation and order derivation
                               ;; stay outside timers. rects/build fills
                               ;; workload defaults; only the seed varies per
                               ;; attempt.
                               snapshot    (-> (rects/build {:seed seed})
                                               (scenes/validate!))
                               ordered     (upload/prepare-scene snapshot)]
                           (when-not (current?)
                             (throw (ex-info "stale" {})))
                           ;; Editor order: view before pool. The timed window
                           ;; covers view, pool, loading lifecycle, batch and
                           ;; tile preparation.
                           (let [u0 (now)]
                             (h/call wasm/internal-module "_set_view"
                                     (:scale view) (- (:x view)) (- (:y view)))
                             (h/call wasm/internal-module "_init_shapes_pool"
                                     (count (:objects snapshot)))
                             (h/call wasm/internal-module "_begin_loading")
                             (try
                               (serialize-shape/serialize-shapes-batch!
                                ordered
                                {:include-layout? false
                                 :include-fills-strokes? true})
                               (finally
                                 (h/call wasm/internal-module "_end_loading")))
                             (h/call wasm/internal-module "_set_view_end")
                             (let [upload-ms (- (now) u0)
                                   r0        (now)]
                               (-> (poll-full epoch 0 r0)
                                   (.then
                                    (fn [poll]
                                      (render-ok module-ms graphics-ms upload-ms
                                                 (live?) poll snapshot seed
                                                 width height dpr renderer))
                                    (fn [_]
                                      (dispose!)
                                      (fail-data {:phase "first-render"
                                                  :message "render threw"})))))))))))
                 (fn [result]
                   result)
                 (fn [cause]
                   (if (= "stale" (ex-message cause))
                     #js {"status" "stale"}
                     (do
                       (dispose!)
                       (fail-data {:phase "aborted"
                                   :message (ex-message cause)}))))))))))
    (catch :default cause
      (js/Promise.resolve
       (fail-data {:phase "setup" :message (ex-message cause)})))))

(defn ping
  "Bridge placeholder that checks the Node/browser boundary."
  []
  #js {"status" "ok"})

(def bridge
  "Browser-side bridge exported from the compiled module."
  #js {"ping" ping
       "loadRects" load-rects
       "dispose" dispose!})
