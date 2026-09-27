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

(defn- teardown-canvas!
  "Releases the context after successful initialization, then removes its
  canvas. The initializer owns cleanup until it returns a context."
  [canvas]
  (when (some? canvas)
    (when-let [context wasm/gl-context]
      (webgl/release-context! wasm/internal-module context wasm/gl-context-handle))
    (try
      (.remove ^js canvas)
      (catch :default _))))

(defn- release-owner-resources!
  "Drops the current owner's canvas, GL handles and WASM module binding
  without touching the owner epoch. Callers own the epoch: `dispose!` bumps
  first, superseding loads already bumped for the new owner."
  []
  (teardown-canvas! @canvas*)
  (wasm/reset-context-state!)
  ;; reset-context-state! deliberately preserves loss state for the editor
  ;; reload path; bench disposal owns it explicitly.
  (reset! wasm/context-lost? false)
  (set! wasm/internal-module nil)
  (reset! canvas* nil))

(defn dispose!
  "Closes the current owner: bumps the epoch (running continuations go
  stale), releases renderer resources and drops the cached module promise so
  a later load re-imports cleanly. Idempotent. Stale callers must not clean
  up a newer owner; use `dispose-if-current!` from async continuations."
  []
  (swap! owner-epoch* inc)
  (release-owner-resources!)
  (reset! module-promise* nil)
  #js {"status" "disposed"})

(defn- dispose-if-current!
  "Disposes only when `epoch` is still current. Stale work returns false
  without modifying the newer owner's canvas, GL context or module cache."
  [epoch]
  (when (= epoch @owner-epoch*)
    (dispose!)
    true))

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
  "Records effective graphics settings: requested CSS size and DPR
  separately from the observed drawing-buffer size and the context's actual
  attributes. Requested defaults live in `webgl/default-context-options`;
  the drawing-buffer size is the context's actual `drawingBufferWidth/Height`,
  not the canvas attributes. What the browser granted comes from
  `getContextAttributes`."
  [ctx width height dpr renderer]
  (let [attrs (try
                (.getContextAttributes ^js ctx)
                (catch :default _ nil))
        attr  (fn [k]
                (when (some? attrs)
                  (unchecked-get attrs k)))]
    {"cssWidth" width
     "cssHeight" height
     "drawingBufferWidth" (.-drawingBufferWidth ^js ctx)
     "drawingBufferHeight" (.-drawingBufferHeight ^js ctx)
     "dpr" dpr
     "webgl2" true
     "antialias" (attr "antialias")
     "depth" (attr "depth")
     "stencil" (attr "stencil")
     "alpha" (attr "alpha")
     "preserveDrawingBuffer" (attr "preserveDrawingBuffer")
     "renderer" renderer
     "software" (software-renderer? renderer)}))

(defn- render-ok
  "Maps one poll result to the bridge value. Success keeps the owner live
  for explicit disposal. Every failure path releases the owner when it still
  owns the realm, so budget exhaustion and context loss never leave a live
  canvas behind. Stale results never dispose: a newer owner is live."
  [epoch module-ms graphics-ms upload-ms alive? poll snapshot seed ctx width height dpr renderer]
  (let [pm (js->clj poll :keywordize-keys true)]
    (cond
      (:stale pm)
      ;; dispose! resets loss state, so stale-plus-lost means genuine
      ;; context loss, not disposal. The loss listener already released the
      ;; canvas; the dispose call below only matters when loss was recorded
      ;; without an epoch bump.
      (if @wasm/context-lost?
        (do
          (dispose-if-current! epoch)
          (fail-data {:phase "context-lost"
                      :message "WebGL context lost during render"}))
        #js {"status" "stale"})

      ;; Safety net: every loss writer bumps the epoch, so staleness
      ;; normally fires first.
      (not alive?)
      (do
        (dispose-if-current! epoch)
        (fail-data {:phase "first-render"
                    :message "disposed or context lost during render"}))

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
        "effectiveGraphics" (read-graphics ctx width height dpr renderer)})

      :else
      (do
        (dispose-if-current! epoch)
        (fail-data {:phase "first-render"
                    :message "Full not reached in budget"
                    :detail pm})))))

(defn- install-listeners!
  "Wires context lost/restored handling for `canvas`, epoch-guarded: a stale
  canvas (removed but not yet GC'd) must not kill the live owner. On loss the
  current owner releases its canvas and GL resources itself: the poll loop
  only observes staleness afterwards, so a `render-ok` cleanup there would
  always miss."
  [canvas epoch]
  (.addEventListener
   canvas "webglcontextlost"
   (let [installed epoch]
     (fn [e]
       (.preventDefault ^js e)
       (when (= installed @owner-epoch*)
         (release-owner-resources!)
         (reset! module-promise* nil)
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

(defn- stale?
  "True when `cause` is the superseded-owner marker from `guard-current!`."
  [cause]
  (true? (::stale (ex-data cause))))

(defn- guard-current!
  "Throws the stale marker when `epoch` no longer owns the realm. Every
  async step calls it first with its own phase so the terminal handler can
  tell superseded work from genuine failures. The marker lives in `ex-data`,
  never in the message: message text is not a protocol."
  [epoch phase]
  (when-not (= epoch @owner-epoch*)
    (throw (ex-info "superseded" {::stale true :phase phase}))))

(def ^:private max-cause-depth
  "How many cause levels the failure cause keeps: the phase wrapper, the
  WASM-error mapping and the original failure."
  3)

(def ^:private max-cause-chars
  "Hard cap for any string in the failure cause, so a hostile value never
  bloats the bridge payload."
  500)

(def ^:private unrenderable-value
  "Fallback for a cause value `pr-str` cannot render."
  "unrenderable value")

(def ^:private unrenderable-cause-text
  "Fallback message when a cause level cannot be rendered."
  "unrenderable cause")

(defn- truncate-cause-str
  "Truncates `s` to `max-cause-chars` without splitting a surrogate pair.
  Never throws."
  [s]
  (try
    (let [text (str s)]
      (if (<= (count text) max-cause-chars)
        text
        (let [cut  (subs text 0 max-cause-chars)
              last (.charCodeAt cut (dec max-cause-chars))]
          (if (and (>= last 0xD800)
                   (<= last 0xDBFF)
                   (let [nxt (.charCodeAt text max-cause-chars)]
                     (and (>= nxt 0xDC00) (<= nxt 0xDFFF))))
            (subs cut 0 (dec max-cause-chars))
            cut))))
    (catch :default _
      unrenderable-value)))

(defn- finite-cause-number?
  "True for numbers the bridge can carry. Mirrors `serializable?`: NaN and
  infinities travel as truncated strings, never as numbers."
  [value]
  (and (number? value)
       (js/isFinite value)))

(defn- sanitize-cause-value
  "Keeps plain-data leaves as-is, stringifies anything else with `pr-str`
  truncation. Never throws."
  [value]
  (try
    (cond
      (or (nil? value) (boolean? value) (keyword? value))
      value

      (string? value)
      (truncate-cause-str value)

      (finite-cause-number? value)
      value

      :else
      (truncate-cause-str (pr-str value)))
    (catch :default _
      unrenderable-value)))

(defn- describe-cause-level
  "Plain-data projection of one `ex-cause` link. Keeps `:message` plus the
  allowlisted WASM details, sanitized. Never throws."
  [cause]
  (try
    (let [message (try (ex-message cause) (catch :default _ nil))
          data    (try (ex-data cause) (catch :default _ nil))
          text    (cond
                    (string? message) message
                    (string? cause)   cause
                    (nil? cause)      "unknown failure"
                    :else             (pr-str cause))
          out     {:message (sanitize-cause-value text)}]
      (if (map? data)
        (reduce (fn [m k]
                  (if (contains? data k)
                    (assoc m k (sanitize-cause-value (get data k)))
                    m))
                out
                [:fn :code :type :hint])
        out))
    (catch :default _
      {:message unrenderable-cause-text})))

(defn- describe-cause
  "Walks at most `max-cause-depth` cause levels, projecting each with
  `describe-cause-level`. Always returns plain data, never throws."
  [cause]
  (try
    (loop [current cause
           depth   0
           acc     []]
      (if (or (nil? current) (>= depth max-cause-depth))
        (if (seq acc)
          acc
          [{:message "unknown failure"}])
        (let [level (describe-cause-level current)
              next  (try (ex-cause current) (catch :default _ nil))]
          (recur next (inc depth) (conj acc level)))))
    (catch :default _
      [{:message unrenderable-cause-text}])))

(defn- terminal-failure
  "Maps a chain rejection to the bridge value. Stale work resolves to the
  stale marker without touching the newer owner; any other failure releases
  the owner when it still owns the realm and resolves to a failure map, so
  the bridge promise never rejects. The top message stays phase-labeled for
  accounting; the original failure lives under `:cause` as plain data. Only
  the top-level cause decides staleness."
  [epoch cause fallback-phase]
  (if (stale? cause)
    #js {"status" "stale"}
    (let [phase   (try (or (:phase (ex-data cause))
                           fallback-phase
                           "aborted")
                       (catch :default _
                         (or fallback-phase "aborted")))
          message (try (or (ex-message cause) "unknown failure")
                       (catch :default _
                         "unknown failure"))
          detail  (try (describe-cause cause)
                       (catch :default _
                         [{:message unrenderable-cause-text}]))]
      (dispose-if-current! epoch)
      (fail-data {:phase   phase
                  :message message
                  :cause   detail}))))

(defn- build-canvas!
  "Creates the bench canvas with a `width*dpr` backing buffer and an explicit
  CSS size, so the drawn scene fills exactly the reported viewport."
  [width height dpr]
  (let [canvas (.createElement js/document "canvas")]
    (aset canvas "width" (* width dpr))
    (aset canvas "height" (* height dpr))
    (aset (.-style ^js canvas) "width" (str width "px"))
    (aset (.-style ^js canvas) "height" (str height "px"))
    canvas))

;; TODO: remove in Ticket 06
(defn load-rects
  "Loads the `:rects/load` scene and renders it to Full. Takes plain data:
  `{\"seed\" int, \"view\" {scale x y viewport?}?, \"module-url\"?, \"wasm-url\"?}`.
  Viewport defaults to 1920x1080@2. Always resolves (failure maps included).

  The chain is flat: one `.then` per phase (module, graphics, scene, upload,
  drain) plus a terminal `.catch`, so every rejection carries its phase to a
  single ownership-aware handler."
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
          ;; Supersede: the epoch bump above already retired the previous
          ;; owner's continuations. Release its canvas and GL resources now
          ;; with the production teardown path, and drop the cached module
          ;; promise so this owner measures a fresh instantiation.
          (release-owner-resources!)
          (reset! module-promise* nil)
          (let [viewport (core/resolve-viewport view)
                width    (:width viewport)
                height   (:height viewport)
                dpr      (:dpr viewport)
                live?    (fn [] (and (= epoch @owner-epoch*) (wasm/live?)))]
            (-> (ensure-module! (or (:module-url params) "./render-wasm.js")
                                (or (:wasm-url params) "./render-wasm.wasm"))
                (.catch (fn [cause]
                          (throw (ex-info (str "module init failed: "
                                               (or (ex-message cause) cause))
                                          {:phase "module-init"}
                                          cause))))
                (.then (fn [installed]
                         (guard-current! epoch "module-init")
                         installed))
                (.then (fn [{:keys [module factory-ms]}]
                         (guard-current! epoch "graphics-init")
                         (set! wasm/internal-module module)
                         (let [canvas (build-canvas! width height dpr)]
                           (.appendChild (.-body js/document) canvas)
                           (reset! canvas* canvas)
                           (let [g0  (now)
                                 ctx (try
                                       (webgl/init-context!
                                        canvas
                                        {:module module
                                         :context-id "webgl2"
                                         :css-width width
                                         :css-height height
                                         :dpr dpr
                                         :flags 0
                                         :browser (sr/translate-browser :chrome)
                                         :params {}})
                                       (catch :default cause
                                         (throw (ex-info "graphics init failed"
                                                         {:phase "graphics-init"}
                                                         cause))))]
                             (if (nil? ctx)
                               (throw (ex-info "WebGL2 context unavailable"
                                               {:phase "graphics-init"}))
                               (do
                                 (set! wasm/gl-context-handle (:handle ctx))
                                 (set! wasm/gl-context (:context ctx))
                                 (set! wasm/canvas canvas)
                                 (mark-live!)
                                 (try
                                   (h/call wasm/internal-module "_resize_viewbox" width height)
                                   (catch :default cause
                                     (throw (ex-info "resize viewbox failed"
                                                     {:phase "graphics-init"}
                                                     cause))))
                                 (install-listeners! canvas epoch)
                                 {:module-ms factory-ms
                                  :graphics-ms (- (now) g0)
                                  :renderer (renderer-string (:context ctx))
                                  :ctx ctx}))))))
                (.then (fn [g]
                         (guard-current! epoch "scene-build")
                         (try
                           (let [snapshot (-> (rects/build {:seed seed})
                                              (scenes/validate!))]
                             (assoc g
                                    :snapshot snapshot
                                    :ordered (upload/prepare-scene snapshot)))
                           (catch :default cause
                             (throw (ex-info "scene build failed"
                                             {:phase "scene-build"}
                                             cause))))))
                (.then (fn [g]
                         (guard-current! epoch "upload")
                         (try
                           (let [u0 (now)]
                             (h/call wasm/internal-module "_set_view"
                                     (:scale view) (- (:x view)) (- (:y view)))
                             (h/call wasm/internal-module "_init_shapes_pool"
                                     (count (:objects (:snapshot g))))
                             (h/call wasm/internal-module "_begin_loading")
                             (try
                               (serialize-shape/serialize-shapes-batch!
                                (:ordered g)
                                {:include-layout? false
                                 :include-fills-strokes? true})
                               (finally
                                 (h/call wasm/internal-module "_end_loading")))
                             (h/call wasm/internal-module "_set_view_end")
                             (assoc g :upload-ms (- (now) u0)))
                           (catch :default cause
                             (if (stale? cause)
                               (throw cause)
                               (throw (ex-info "upload failed"
                                               {:phase "upload"}
                                               cause)))))))
                (.then (fn [g]
                         (guard-current! epoch "first-render")
                         (let [{:keys [module-ms graphics-ms renderer ctx snapshot upload-ms]} g
                               r0 (now)]
                           (-> (poll-full epoch 0 r0)
                               (.then (fn [poll]
                                        (render-ok epoch module-ms graphics-ms upload-ms
                                                   (live?) poll snapshot seed
                                                   (:context ctx)
                                                   width height dpr renderer)))
                               (.catch (fn [cause]
                                         (terminal-failure epoch cause "first-render")))))))
                (.catch (fn [cause]
                          (terminal-failure epoch cause "aborted"))))))))
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
