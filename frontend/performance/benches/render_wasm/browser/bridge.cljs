;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.browser.bridge
  "Prepares renderer scenes and executes benchmark case phases in the browser.

  A scene is a recipe that builds a snapshot. A snapshot contains the concrete
  shapes in `:objects` and label references in `:refs`. A case pairs a scene
  with parameters, a camera view and execution settings. A collected case
  descriptor reaches the browser through Transit; this namespace resolves the
  matching local registration, which supplies the `run!` function and optional
  preparation callback and reset function.

  A preparation owns one renderer lifetime: the instantiated module, canvas,
  WebGL2 context, uploaded snapshot and command context. It loads the module,
  creates graphics, builds and uploads the scene, runs the preparation callback
  once, and then renders an initial Full frame unless the case declares
  `:preparation {:render :none}`. Fresh cases prepare once per attempt. Reused
  cases prepare once and carry the returned view and command context into later
  attempts.

  Execution has up to three phases: preparation, main and reset. Main runs the
  case's `run!` function. Reset follows each successful main execution. A
  phase's raw block contains `:phase`, `:preparation-id`, `:outcome`,
  selected command `:events` and, when present, `:diagnostics`. A successful
  preparation exposes its blocks at `[:preparation :phases]`. A failed
  preparation places its available blocks at the top level. An attempt places
  main and optional reset blocks at the top level and keeps the preparation
  evidence captured for that attempt.

  A command context has exactly four fields: `:case`, the collected case
  descriptor; `:scene`, the snapshot; `:view`, the camera scale and offsets;
  and `:hooks`, the functions for time, scheduling, checks and renderer
  operations. Every command receives the full hook map with its context. The
  context, module, graphics handles, canvas and snapshot stay in the browser.
  Only projected evidence, such as preparation data, phase blocks, identity
  data and summary or comparison data, crosses Transit.

  An owner is the current page lifetime. Lifecycle guards compare a captured
  owner epoch with the current epoch; a mismatch throws a top-level
  `::errors/stale` marker. A newer load, disposal or WebGL context loss
  increments the epoch and supersedes the old owner. Only a top-level
  `::errors/stale` marker yields a stale result. A superseded operation that
  rejects for another reason returns failed evidence for that operation and
  leaves the newer owner in place; a failed attempt keeps its own preparation
  and phase blocks. An ordinary failure in the current owner ends that owner
  and returns its phase, cause and available partial evidence.

  Renderer-call measurements end when each call returns. Setup diagnostics
  retain separate intervals for module initialization, graphics setup, upload
  and initial rendering. GPU execution and display proceed asynchronously
  after renderer submission.

  The exported `bridge` object maps the names used by the runner to this public
  interface; the runner calls it through `page.evaluate`. Report formatting
  entries return text; the other entries return Transit text or, for `ping` and
  `dispose`, status objects."
  (:require-macros
   [app.common.exceptions :as ex])
  (:require
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   ;; bind the enum table from the prepared renderer build's `shared.js` so
   ;; a missing `shared.js` fails the compile. A stale or mismatched one throws
   ;; at load
   [app.render-wasm.api.enums]
   [app.render-wasm.api.webgl :as webgl]
   [benches.render-wasm.cases]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.declarations :as decl]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [benches.render-wasm.fingerprint :as fingerprint]
   [benches.render-wasm.identity :as identity]
   [benches.render-wasm.report.compare :as compare]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [benches.render-wasm.runtime.commands :as commands]
   [benches.render-wasm.runtime.protocol :as protocol]
   [benches.render-wasm.snapshot :as snapshot]))

;; Forward decls
(declare now guard-current! read-graphics)

(def schema:load-scene-args
  "Decoded form of the Transit request accepted by `load-scene` and
  `prepare-scene-request`.

  `:case` is a collected case descriptor. `:preparation-id` labels the
  preparation and defaults to the owner epoch. `:timeout-ms` bounds setup and,
  through the stored preparation, later attempts that omit their own timeout.
  `:module-url` and `:wasm-url` override the renderer artifacts."
  [:map {:closed true}
   [:case decl/schema:collected-case]
   [:preparation-id {:optional true} some?]
   [:timeout-ms {:optional true} [:int {:min 1}]]
   [:module-url {:optional true} string?]
   [:wasm-url {:optional true} string?]])

(def ^:const ^:private case-ms-budget
  "Default elapsed-time deadline for setup or one attempt, checked by
  `browser-hooks`. Requests can supply a timeout. The outer process timeout
  covers blocked pages."
  30000)

(defn- browser-hooks
  "Returns the command hooks for one owner epoch and deadline.

  Args:
    epoch: owner epoch that `:check` compares with the current epoch.
    deadline-ms: deadline in milliseconds on the `now` clock.

  Returns:
    A map with `:now`, `:frame`, `:sleep`, `:check`, `:render`,
    `:render-sync`, `:render-from-cache`, `:set-view`, `:set-view-start` and
    `:set-view-end`. `:check` rejects work after the owner is superseded, the
    WebGL context is lost or the deadline passes."
  [epoch deadline-ms]
  {:now               now
   ;; rAF scheduling
   :frame             #(js/Promise.
                        (fn [resolve] (.requestAnimationFrame js/window resolve)))
   :sleep             #(js/Promise. (fn [resolve] (js/setTimeout resolve %)))
   ;; checks that this execution still owns the page, WebGL context hasn't been
   ;; lost, and the deadline hasn't passed
   :check             (fn []
                        (when @wasm/context-lost?
                          (throw (ex-info "WebGL context lost"
                                          {:phase "context-lost"})))
                        (guard-current! epoch "protocol")
                        (when (>= (now) deadline-ms)
                          (throw (ex-info "case deadline exceeded"
                                          {:phase "deadline"}))))
   ;; WASM calls
   :render            (fn [timestamp flags]
                        (h/call wasm/internal-module "_render" timestamp flags))
   :render-sync       #(h/call wasm/internal-module "_render_sync")
   :render-from-cache #(h/call wasm/internal-module "_render_from_cache" 0)
   :set-view          #(h/call wasm/internal-module "_set_view"
                               (:scale %) (- (:x %)) (- (:y %)))
   :set-view-start    #(h/call wasm/internal-module "_set_view_start")
   :set-view-end      #(h/call wasm/internal-module "_set_view_end")})


;; Monotonic owner generation. Async continuations capture the current epoch
;; and compare it before acting; a mismatch marks their work stale.
(defonce ^:private owner-epoch*
  (atom 0))

;; Cached Emscripten import. Import precedes module-init timing (untimed).
(defonce ^:private module-promise*
  (atom nil))

;; Page-scoped canvas owned by the current load. :fresh cases load once per
;; page. :reuse cases keep one preparation across all attempts.
(defonce ^:private canvas*
  (atom nil))

;; Current completed preparation for `run-prepared`: owner, case, reusable
;; command context and browser-local graphics.
(defonce ^:private prepared*
  (atom nil))


(defn- mark-live!
  "Marks the current renderer context as initialized and available."
  []
  (reset! wasm/context-lost? false)
  (set! wasm/context-initialized? true))

(defn- now
  "Returns the browser's monotonic clock in milliseconds."
  []
  (.now js/performance))

(defn- ensure-module!
  "Imports the Emscripten glue and instantiates it with the prepared `.wasm`
  bytes.

  The import is cached per page and untimed: script import precedes
  module-init timing. `locateFile` points at `wasm-url`.

  Args:
    module-url: URL of the Emscripten glue module.
    wasm-url: URL of the `.wasm` file passed to `locateFile`.

  Returns:
    A Promise of `{:module instance :factory-ms ms}`, where `:factory-ms` is
    the instantiation duration in milliseconds. A failed import or factory
    call rejects the Promise."
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

(defn- graphics-string
  "Reads a WebGL string parameter.

  Args:
    context: WebGL context to query.
    parameter: property name on `WEBGL_debug_renderer_info`, such as
      `\"UNMASKED_RENDERER_WEBGL\"`.

  Returns:
    The parameter's string value, or `\"unknown\"` when the extension is
    unavailable or the read throws."
  [context parameter]
  (or (ex/ignoring
       (when-let [ext (.getExtension ^js context "WEBGL_debug_renderer_info")]
         (str (.getParameter ^js context (unchecked-get ext parameter)))))
      "unknown"))

(defn- renderer-string
  "Reads the unmasked WebGL renderer identity.

  Args:
    context: WebGL context to query.

  Returns:
    The unmasked renderer text, or `\"unknown\"` when the debug extension is
    unavailable or the read throws."
  [context]
  (graphics-string context "UNMASKED_RENDERER_WEBGL"))

(defn- software-renderer?
  "Recognizes common software graphics implementations from their renderer text.

  Args:
    s: renderer text to inspect; nil is treated as empty.

  Returns:
    `true` when the text names SwiftShader, llvmpipe, generic software or a
    basic render, ignoring case; `false` otherwise."
  [s]
  (boolean (re-find #"(?i)swiftshader|llvmpipe|software|basic render" (or s ""))))

(defn- teardown-canvas!
  "Releases the context after successful initialization, then removes its
  canvas.

  The initializer owns cleanup until it returns a context.

  Args:
    canvas: canvas to remove; nil skips both steps.

  Returns:
    nil after the cleanup."
  [canvas]
  (when (some? canvas)
    (when-let [context wasm/gl-context]
      (webgl/release-context! wasm/internal-module context wasm/gl-context-handle))
    (ex/ignoring (.remove ^js canvas))))

(defn- release-owner-resources!
  "Releases the current owner's canvas, GL handles, module binding and
  stored preparation.

  Returns:
    nil after releasing the resources."
  []
  (teardown-canvas! @canvas*)
  (wasm/reset-context-state!)
  ;; reset-context-state! deliberately preserves loss state for the editor
  ;; reload path; bench disposal owns it explicitly.
  (reset! wasm/context-lost? false)
  (set! wasm/internal-module nil)
  (reset! canvas* nil)
  (reset! prepared* nil))

(defn dispose!
  "Closes the current owner and returns a JavaScript status object.

  It bumps the epoch, so running continuations go stale, releases renderer
  resources and drops the cached module promise so a later load re-imports
  cleanly. Idempotent. Stale callers must not clean up a newer owner; use
  `dispose-if-current!` from async continuations.

  Returns:
    A JavaScript object with status `\"disposed\"`."
  []
  (swap! owner-epoch* inc)
  (release-owner-resources!)
  (reset! module-promise* nil)
  #js {"status" "disposed"})

(defn- dispose-if-current!
  "Disposes only when `epoch` is still current.

  Args:
    epoch: owner epoch captured by the caller.

  Returns:
    `true` after disposing, or nil for stale work. Stale work leaves the newer
    owner's canvas, GL context and module cache unchanged."
  [epoch]
  (when (= epoch @owner-epoch*)
    (dispose!)
    true))


(defn- read-graphics
  "Records the effective graphics settings for a live WebGL context.

  The result separates the requested CSS size and device pixel ratio from the
  observed drawing-buffer size and the context's actual attributes. Requested
  defaults live in `webgl/default-context-options`; the drawing-buffer size is
  the context's actual `drawingBufferWidth` and `drawingBufferHeight`, not the
  canvas attributes. Granted attributes come from `getContextAttributes`.

  Args:
    ctx: live WebGL context to inspect.
    width: requested CSS width in pixels.
    height: requested CSS height in pixels.
    dpr: requested device pixel ratio.
    renderer: unmasked renderer text to record.

  Returns:
    A map with the requested `:css-width`, `:css-height` and `:dpr`; the
    observed `:drawing-buffer-width` and `:drawing-buffer-height`; `:webgl2`
    set to `true`; the granted `:antialias`, `:depth`, `:stencil`, `:alpha`
    and `:preserve-drawing-buffer` attributes; and the `:renderer`, `:vendor`
    and `:software` identity fields."
  [ctx width height dpr renderer]
  (let [attrs (ex/ignoring (.getContextAttributes ^js ctx))
        attr  #(when (some? attrs)
                 (unchecked-get attrs %))
        vendor (graphics-string ctx "UNMASKED_VENDOR_WEBGL")]
    {:css-width width
     :css-height height
     :drawing-buffer-width (.-drawingBufferWidth ^js ctx)
     :drawing-buffer-height (.-drawingBufferHeight ^js ctx)
     :dpr dpr
     :webgl2 true
     :antialias (attr "antialias")
     :depth (attr "depth")
     :stencil (attr "stencil")
     :alpha (attr "alpha")
     :preserve-drawing-buffer (attr "preserveDrawingBuffer")
     :renderer renderer
     :vendor vendor
     :software (software-renderer? renderer)}))

(defn- install-listeners!
  "Wires context lost/restored handling for `canvas`, guarded by the epoch.

  A stale canvas leaves the live owner untouched. On loss the current owner
  releases its canvas and GL resources, clears the module promise, marks the
  context lost and supersedes its epoch immediately. A restore event
  supersedes the epoch when its owner still matches.

  Args:
    canvas: canvas that receives the listeners.
    epoch: owner epoch captured when the listeners are installed.

  Returns:
    nil after installing both listeners."
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

(defn- terminal-failure
  "Returns the bridge result for a rejected asynchronous step.

  Only a top-level `::errors/stale` marker decides staleness. A stale cause
  returns `{:status \"stale\"}` and leaves the newer owner in place. Any other
  cause disposes the owner when `epoch` is still current and returns failed
  evidence. The evidence uses the phase from the cause, or `fallback-phase`
  when absent, and keeps the original failure as plain data under `:cause`.
  The function attaches stored preparation evidence when that preparation
  belongs to `epoch`.

  Args:
    epoch: owner epoch that the failed step belongs to.
    cause: rejected value from the step.
    fallback-phase: phase name used when the cause has no `:phase`.

  Returns:
    `{:status \"stale\"}` for a top-level stale cause; otherwise failed
    evidence with status `\"failed\"`, `:phase`, `:message`, `:cause`, optional
    `:partial` data and optional `:preparation` evidence."
  [epoch cause fallback-phase]
  (if (errors/stale? cause)
    {:status "stale"}
    (let [phase       (or (ex/ignoring (:phase (ex-data cause))) fallback-phase "aborted")
          prepared    @prepared*
          preparation (when (= epoch (get-in prepared [:owner :epoch]))
                        (get-in prepared [:graphics :preparation]))]
      (dispose-if-current! epoch)
      (cond-> (errors/fail-data (errors/evidence phase cause))
        preparation (assoc :preparation preparation)))))

(defn- guard-current!
  "Throws the stale marker when `epoch` no longer owns the page.

  Callers pass their phase so the terminal handler can tell superseded work
  from genuine failures. The marker is `::errors/stale` in `ex-data`; message
  text is for humans only.

  Args:
    epoch: owner epoch captured by the caller.
    phase: phase name recorded in the stale marker.

  Returns:
    nil when `epoch` is current.

  Throws:
    An exception when `epoch` is stale, with `::errors/stale true` and
    `:phase` in its `ex-data`."
  [epoch phase]
  (when-not (= epoch @owner-epoch*)
    (throw (ex-info "superseded" {::errors/stale true :phase phase}))))

(defn- build-canvas!
  "Creates the bench canvas with a `width*dpr` backing buffer and an explicit
  CSS size, so the drawn scene fills exactly the reported viewport.

  Args:
    width: viewport width in CSS pixels.
    height: viewport height in CSS pixels.
    dpr: device pixel ratio used for the backing buffer.

  Returns:
    An unattached canvas element whose backing buffer is `width*dpr` by
    `height*dpr` and whose CSS size is `width` by `height` pixels."
  [width height dpr]
  (let [canvas (.createElement js/document "canvas")]
    (aset canvas "width" (* width dpr))
    (aset canvas "height" (* height dpr))
    (aset (.-style ^js canvas) "width" (str width "px"))
    (aset (.-style ^js canvas) "height" (str height "px"))
    canvas))

(defn- ^:async run-phase
  "Executes one lifecycle phase through the command executor.

  `run` receives a command context and returns the next context or a Promise
  of it. The raw block keeps `:phase`, `:preparation-id`, `:outcome`,
  selected command `:events` and optional `:diagnostics`, and is appended to
  `phases`.

  Args:
    phase: lifecycle phase name, such as `:preparation`, `:main` or `:reset`.
    preparation-id: identifier recorded in the raw block.
    rtx: command context supplied to the command executor.
    run: function from a command context to the next context or a Promise.
    phases: volatile that receives each raw phase block.

  Returns:
    A Promise of the next command context. A failed command outcome rejects
    the Promise with a phase-labeled error after the block is recorded."
  [phase preparation-id rtx run phases]
  (let [outcome (await (commands/execute rtx run))
        block   (assoc (select-keys outcome [:outcome :events :diagnostics])
                       :phase phase :preparation-id preparation-id)]
    (vswap! phases conj block)
    (when (= :failed (:outcome outcome))
      (let [cause (:error outcome)]
        (throw (errors/with-context cause (errors/message-of cause "phase failed")
                 {:phase (or (:phase (ex-data cause)) (name phase))}))))
    (:context outcome)))

(defn- ^:async run-case
  "Runs main and the optional reset against one prepared command context.

  Main executes the local case's `run!` function. Reset executes only after a
  successful main. The context returned by main, or by reset when present, is
  stored for the next reused attempt, so camera changes carry over.

  Args:
    owner: map with `:epoch`, the owner epoch, and `:hooks`, the hooks used by
      main and reset.
    case-desc: collected case descriptor.
    local-case: local case registration with `run!` and optional `:reset`.
    graphics: browser-local graphics map containing the preparation evidence
      and snapshot.
    context: prepared command context for main.

  Returns:
    A Promise of status `\"ok\"` with `:preparation` evidence, `:phases` for
    main and any reset, `:scene` with the shape count in `:shapes` and the
    seed in `:seed`, and `:effective-graphics`. A non-stale failure resolves
    to failed evidence with this attempt's preparation and blocks, even when
    a newer owner exists. A top-level `::errors/stale` marker resolves to the
    stale result."
  [{:keys [epoch hooks]} case-desc local-case graphics context]
  (let [phases         (volatile! [])
        preparation    (:preparation graphics)
        preparation-id (:preparation-id preparation)]
    (try
      (let [rtx (assoc context :hooks hooks)
            rtx (await (run-phase :main preparation-id rtx (:run! local-case) phases))
            rtx (if-some [reset (:reset local-case)]
                  (await (run-phase :reset preparation-id rtx reset phases))
                  rtx)]
        (guard-current! epoch "main")
        (when-not (wasm/live?)
          (throw (ex-info "disposed or context lost during case" {:phase "main"})))
        (swap! prepared* assoc :context rtx)
        {:status "ok"
         :preparation preparation
         :phases @phases
         :scene {:shapes (count (:objects (:snapshot graphics)))
                 :seed (:scene-seed case-desc)}
         :effective-graphics (:effective-graphics preparation)})
      (catch :default cause
        (cond-> (terminal-failure epoch cause "main")
          (not (errors/stale? cause)) (assoc :preparation preparation :phases @phases))))))

(defn- parse-load-request
  "Decodes and validates a Transit scene request before claiming the page.

  Args:
    request: Transit string containing the fields in
      `schema:load-scene-args`.

  Returns:
    A map with `:params`, `:case-desc`, `:local-case` and `:scene`, or the
    validation Error. Failure leaves the current owner in place."
  [request]
  (attempt
   (let [params (t/decode-str request)]
     (when-not (sm/validate schema:load-scene-args params)
       (throw (ex-info "load-scene needs a Transit request with case"
                       {:phase "invalid-args"})))
     (let [case-desc (:case params)
           scene     (decl/registered-scene (:scene case-desc))
           local     (decl/registered-case (:id case-desc))]
       (when (nil? scene)
         (throw (ex-info (str "unknown scene: " (:scene case-desc))
                         {:phase "invalid-args"})))
       (when-not (and local
                      (fn? (:run! local))
                      (= (:scene local) (:scene case-desc)))
         (throw (ex-info (str "missing case body for " (:id case-desc))
                         {:phase "invalid-args"})))
       (decl/check-registered-case! local)
       {:params params
        :case-desc case-desc
        :local-case local
        :scene scene}))))

(defn- begin-load!
  "Claims the page, releases the prior owner and resolves the viewport.

  It increments the owner epoch, releases the prior owner's canvas, graphics
  resources and preparation, and clears the module promise so the next load
  imports fresh. The caller supplies hooks and a deadline.

  Args:
    case-desc: collected case descriptor whose `:view` supplies viewport
      overrides.

  Returns:
    A map with `:epoch`, the new owner epoch, and `:dims`, the resolved
    viewport with `:width`, `:height` and `:dpr`."
  [case-desc]
  (let [epoch (swap! owner-epoch* inc)]
    (release-owner-resources!)
    (reset! module-promise* nil)
    (let [viewport (decl/resolve-viewport (:view case-desc))]
      {:epoch epoch
       :dims viewport})))

(defn- load-module
  "Loads and instantiates the renderer.

  Args:
    epoch: owner epoch checked after the module resolves.
    params: decoded request map; `:module-url` and `:wasm-url` override the
      renderer artifact URLs.

  Returns:
    A Promise of the module map with `:module` and `:factory-ms`. A failed
    import or factory call rejects with the module-init phase and its cause
    for bridge accounting; a superseded owner rejects with the stale marker."
  [epoch params]
  (-> (ensure-module! (or (:module-url params) "./render-wasm.js")
                      (or (:wasm-url params) "./render-wasm.wasm"))
      (.catch #(throw (ex-info (str "module init failed: " (or (ex-message %) %))
                               {:phase "module-init"}
                               %)))
      (.then (fn [installed]
               (guard-current! epoch "module-init")
               installed))))

(defn- initialize-graphics!
  "Creates the canvas and WebGL context and binds them to the renderer.

  Args:
    epoch: owner epoch used by the context-loss listeners.
    viewport: map with `:width`, `:height` and `:dpr`.
    installed: module map with `:module` and `:factory-ms`.

  Returns:
    A map with `:module-ms`, the factory instantiation duration in
    milliseconds; `:graphics-ms`, the context creation, renderer binding,
    viewbox resize and listener installation duration in milliseconds;
    `:renderer`, the unmasked renderer text; and `:ctx`, the graphics
    resources kept in the browser.

  Throws:
    The stale marker when `epoch` is no longer current. Context creation or
    viewbox resize failures throw an exception with phase `graphics-init`."
  [epoch {:keys [width height dpr]} {:keys [module factory-ms]}]
  (guard-current! epoch "graphics-init")
  (set! wasm/internal-module module)
  (let [canvas (build-canvas! width height dpr)]
    (.appendChild (.-body js/document) canvas)
    (reset! canvas* canvas)
    (let [g0  (now)
          ctx (try
                (webgl/init-context
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
      (when (nil? ctx)
        (throw (ex-info "WebGL2 context unavailable"
                        {:phase "graphics-init"})))
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
       :ctx ctx})))

(defn- prepare-scene
  "Builds the registered scene, then fingerprints and orders its shapes.

  The scene builder returns a validated snapshot. Fingerprinting and upload
  order derivation finish before the upload timer begins.

  Args:
    epoch: owner epoch checked before the scene is built.
    scene: registered scene declaration with a `:build` function.
    case-desc: collected case descriptor whose `:params` are passed to the
      builder.
    seed: scene seed supplied as `:seed` to the builder.
    graphics: graphics map to extend.

  Returns:
    `graphics` with `:snapshot`, `:fingerprint` and `:ordered`.

  Throws:
    The stale marker when `epoch` is no longer current. Other scene
    preparation failures throw an exception with phase `scene-build`."
  [epoch scene case-desc seed graphics]
  (guard-current! epoch "scene-build")
  (try
    (let [snapshot ((:build scene) (assoc (:params case-desc) :seed seed))]
      (assoc graphics
             :snapshot snapshot
             :fingerprint (fingerprint/fingerprint snapshot)
             :ordered (snapshot/upload-order snapshot)))
    (catch :default cause
      (if (errors/stale? cause)
        (throw cause)
        (throw (ex-info "scene build failed"
                        {:phase "scene-build"}
                        cause))))))

(defn- upload-scene!
  "Sets the initial view and uploads prepared shapes inside the upload timer.

  The timer covers view setup, pool initialization, the loading lifecycle and
  batch serialization. The loading lifecycle ends even when serialization
  fails.

  Args:
    epoch: owner epoch checked before uploading.
    case-desc: collected case descriptor whose `:view` sets the camera.
    graphics: prepared graphics map with `:snapshot` and `:ordered`.

  Returns:
    `graphics` with `:upload-ms`, the upload duration in milliseconds, and
    `:view`, the camera view that was set.

  Throws:
    The stale marker when `epoch` is no longer current. Other upload failures
    throw an exception with phase `upload`."
  [epoch case-desc graphics]
  (guard-current! epoch "upload")
  (try
    (let [u0   (now)
          view (:view case-desc)]
      (h/call wasm/internal-module "_set_view"
              (:scale view) (- (:x view)) (- (:y view)))
      (h/call wasm/internal-module "_init_shapes_pool"
              (count (:objects (:snapshot graphics))))
      (h/call wasm/internal-module "_begin_loading")
      (try
        ;; Current procedural scenes upload without layout data.
        (serialize-shape/serialize-shapes-batch
         (:ordered graphics)
         {:include-layout? false
          :include-fills-strokes? true})
        (finally
          (h/call wasm/internal-module "_end_loading")))
      (h/call wasm/internal-module "_set_view_end")
      (assoc graphics :upload-ms (- (now) u0) :view view))
    (catch :default cause
      (if (errors/stale? cause)
        (throw cause)
        (throw (ex-info "upload failed"
                        {:phase "upload"}
                        cause))))))

(defn- preparation-data
  "Projects completed setup into preparation evidence.

  Args:
    id: preparation identifier.
    case-desc: collected case descriptor.
    effective-graphics: graphics facts read from the live context.
    graphics: browser-local graphics map with timings, snapshot, fingerprint
      and optional `:setup-render`.
    phases: raw preparation phase blocks.

  Returns:
    Portable identity data, graphics facts, raw phase blocks and setup
    diagnostics."
  [id case-desc effective-graphics graphics phases]
  (let [{:keys [module-ms graphics-ms upload-ms snapshot fingerprint setup-render]} graphics]
    {:preparation-id id
     :case-id (:id case-desc)
     :phases phases
     :fingerprint fingerprint
     :effective-graphics effective-graphics
     :diagnostics (cond-> {:module-init-ms module-ms :graphics-init-ms graphics-ms
                           :upload-ms upload-ms
                           :scene {:shapes (count (:objects snapshot)) :seed (:scene-seed case-desc)}}
                    setup-render (assoc :setup-render setup-render))}))

(defn- command-context
  "Returns the four-field command context for one preparation.

  Args:
    case-desc: collected case descriptor.
    hooks: browser hooks for the current owner and deadline.
    graphics: prepared graphics map containing `:snapshot` and `:view`.

  Returns:
    The command context with the uploaded snapshot in `:scene` and the
    current camera view in `:view`."
  [case-desc hooks graphics]
  {:case case-desc
   :scene (:snapshot graphics)
   :view (:view graphics)
   :hooks hooks})

(defn- ^:async prepare-initial-view
  "Runs the preparation callback, then renders the initial Full frame.

  Args:
    callback: function from a command context to the next context or a
      Promise.
    render-mode: `:none` skips the initial render; every other value renders.
    setup-render: volatile that receives the drain trace when rendering runs.
    rtx: command context passed to the callback.

  Returns:
    A Promise of the callback's resulting context. The drain uses that
    context's hooks, starts with flags 0 and records its traces and durations
    in `setup-render`. A callback or drain failure rejects the Promise."
  [callback render-mode setup-render rtx]
  (let [rtx (await (callback rtx))]
    (when-not (= :none render-mode)
      (let [hooks (:hooks rtx)]
        (vreset! setup-render
                 (await (protocol/drain hooks {:flags 0
                                               :origin ((:now hooks))
                                               :immediate false})))))
    rtx))

(defn- ^:async prepare-context
  "Runs the preparation phase and attaches its context to `graphics`.

  The local case supplies the preparation callback; `identity` is used when
  none is registered.

  Args:
    preparation: map with `:owner`, `:case-desc`, `:local-case` and
      `:preparation-id`.
    phases: volatile that receives the raw phase block.
    graphics: prepared graphics map with the uploaded snapshot and view.

  Returns:
    A Promise of `graphics` with `:context`, the context returned by the
    callback and initial render, and `:setup-render`, the automatic render
    trace or nil. A failed preparation phase rejects the Promise."
  [{:keys [owner case-desc local-case preparation-id]} phases graphics]
  (let [setup-render (volatile! nil)
        callback     (get-in local-case [:preparation :callback] identity)
        render-mode  (get-in case-desc [:preparation :render])
        context      (command-context case-desc (:hooks owner) graphics)
        context      (await (run-phase :preparation preparation-id context
                                       (partial prepare-initial-view callback render-mode setup-render)
                                       phases))]
    (assoc graphics :context context :setup-render @setup-render)))

(defn- prepared-state
  "Collects the reusable context, graphics resources and preparation evidence.

  Args:
    preparation: case definitions, owner and timeout settings.
    graphics: prepared graphics and the completed command context.
    evidence: portable preparation evidence.

  Returns:
    State ready for its first execution, with the context stored separately
    from graphics."
  [preparation graphics evidence]
  (assoc (select-keys preparation [:owner :case-desc :local-case :timeout-ms])
         :busy? false
         :used? false
         :context (:context graphics)
         :graphics (assoc (dissoc graphics :context) :preparation evidence)))

(defn- complete-preparation
  "Reads effective graphics, records the completed preparation and returns it.

  Args:
    preparation: map with `:owner`, `:case-desc` and `:preparation-id`.
    phases: volatile of raw preparation blocks.
    graphics: prepared graphics map with the live context and setup facts.

  Returns:
    `{:status \"ok\" :preparation evidence}` after storing the preparation for
    later attempts.

  Throws:
    A top-level stale marker when the owner changed during preparation."
  [{:keys [owner case-desc preparation-id] :as preparation} phases graphics]
  (guard-current! (:epoch owner) "prepare")
  (let [{:keys [width height dpr]} (:dims owner)
        {:keys [ctx renderer]}     graphics
        effective-graphics         (read-graphics (:context ctx) width height dpr renderer)
        evidence                   (preparation-data preparation-id case-desc effective-graphics graphics @phases)]
    (reset! prepared* (prepared-state preparation graphics evidence))
    {:status "ok" :preparation evidence}))

(defn- record-setup
  "Records setup facts for partial failure evidence and returns `graphics`.

  The stored keys are `:module-ms`, `:graphics-ms`, `:fingerprint` and
  `:upload-ms` when present.

  Args:
    available: volatile map that receives the available setup facts.
    graphics: graphics map to read the facts from.

  Returns:
    `graphics`, unchanged."
  [available graphics]
  (vreset! available (select-keys graphics [:module-ms :graphics-ms :fingerprint :upload-ms]))
  graphics)

(defn- preparation-failure
  "Returns failure evidence for a preparation that did not complete.

  A top-level stale cause returns the stale result.

  Args:
    epoch: owner epoch that the preparation belongs to.
    available: volatile of setup facts recorded before the failure.
    phases: volatile of raw preparation blocks.
    cause: failure that ended preparation.

  Returns:
    `{:status \"stale\"}` for a top-level stale cause; otherwise failed
    evidence that keeps the recorded setup facts under `:partial :setup`, any
    partial render data from the cause, and the raw preparation blocks at the
    top level under `:phases`. It releases the owner when `epoch` is still
    current."
  [epoch available phases cause]
  (if (errors/stale? cause)
    (terminal-failure epoch cause "aborted")
    (assoc (terminal-failure epoch
                             (errors/with-context cause (errors/message-of cause "setup failed")
                               {:partial {:setup @available}})
                             "aborted")
           :phases @phases)))

(defn- ^:async prepare-owned-scene
  "Prepares a claimed scene from a supplied module installation Promise.

  Args:
    preparation: map with `:owner`, `:case-desc`, `:scene`, `:local-case` and
      `:preparation-id`.
    module-promise: Promise of the installed module map; the function awaits it
      before graphics initialization and scene preparation.

  Returns:
    A Promise of `{:status \"ok\" :preparation evidence}` on success.
    Ordinary failure returns failed evidence with the failed phase, any
    recorded setup facts under `:partial :setup`, and the raw preparation
    blocks at the top level under `:phases`. A top-level stale marker produces
    the stale result."
  [{:keys [owner case-desc scene] :as preparation} module-promise]
  (let [{:keys [epoch dims]} owner
        available            (volatile! {})
        phases               (volatile! [])]
    (try
      (let [module   (await module-promise)
            graphics (->> module
                          (initialize-graphics! epoch dims)
                          (record-setup available))
            graphics (->> graphics
                          (prepare-scene epoch scene case-desc (:scene-seed case-desc))
                          (record-setup available))
            graphics (->> graphics
                          (upload-scene! epoch case-desc)
                          (record-setup available))
            graphics (await (prepare-context preparation phases graphics))]
        (complete-preparation preparation phases graphics))
      (catch :default cause
        (preparation-failure epoch available phases cause)))))

(defn- prepare-request
  "Validates a Transit scene request, claims the page and prepares the scene.

  Validation runs before the owner changes. A valid request releases the prior
  owner, starts a new owner epoch, builds browser hooks with a deadline and
  prepares the scene. `:preparation-id` defaults to the new epoch and
  `:timeout-ms` defaults to `case-ms-budget`.

  Args:
    request: Transit string matching `schema:load-scene-args`.

  Returns:
    A Promise of `{:status \"ok\" :preparation evidence}`, failed evidence with
    a phase, message, cause and optional partial data, or the stale result. A
    preparation failure also carries its available blocks under top-level
    `:phases`. Invalid arguments resolve as failed `invalid-args` evidence and
    leave the current owner untouched."
  [request]
  (try
    (let [parsed (parse-load-request request)]
      (if (failure? parsed)
        (js/Promise.resolve (errors/fail-data (errors/evidence "invalid-args" parsed)))
        (let [{:keys [params case-desc]} parsed
              {:keys [epoch] :as owner}  (begin-load! case-desc)
              timeout-ms                 (or (:timeout-ms params) case-ms-budget)
              deadline-ms                (+ (now) timeout-ms)
              owner                      (assoc owner :deadline-ms deadline-ms
                                                :hooks (browser-hooks epoch deadline-ms))]
          (prepare-owned-scene (assoc parsed
                                      :owner owner
                                      :timeout-ms timeout-ms
                                      :preparation-id (or (:preparation-id params) epoch))
                               (load-module epoch params)))))
    (catch :default cause
      (js/Promise.resolve (errors/fail-data (errors/evidence "setup" cause))))))

(defn- execute-prepared
  "Runs main and optional reset against the stored preparation.

  Args:
    preparation-id: identifier of the prepared scene to use.
    timeout-ms: optional remaining budget in milliseconds, bounded by the
      preparation's case deadline.

  Returns:
    A Promise of status ok with `:preparation` evidence, top-level `:phases`
    for main and any reset, `:scene` shape count and seed, and
    `:effective-graphics`; or failed evidence. A top-level stale marker
    produces the stale result.

  A missing, busy or mismatched preparation, or a fresh preparation already
  used, resolves as failed `invalid-args` evidence, leaving the owner
  unchanged. A non-stale phase failure releases that preparation, so later
  attempts find it unavailable."
  [{:keys [preparation-id timeout-ms]}]
  (let [{:keys [owner case-desc local-case graphics context busy? used?] :as prepared} @prepared*
        id (get-in graphics [:preparation :preparation-id])]
    (if (or (nil? prepared) (not= id preparation-id) busy?
            (and used? (= :fresh (:context case-desc))))
      (js/Promise.resolve (errors/fail-data {:phase "invalid-args"
                                             :message "preparation unavailable, busy or already used"}))
      (let [epoch (:epoch owner)
            deadline-ms (if timeout-ms
                          (min (:deadline-ms owner) (+ (now) timeout-ms))
                          (:deadline-ms owner))
            owner (assoc owner :hooks (browser-hooks epoch deadline-ms))]
        (swap! prepared* assoc :busy? true :used? true)
        (-> (run-case owner case-desc local-case graphics context)
            (.then (fn [evidence]
                     (when (= epoch @owner-epoch*)
                       (swap! prepared* assoc :busy? false))
                     evidence))
            (.catch #(terminal-failure epoch % "attempt")))))))

(defn- encode-evidence
  "Encodes bridge evidence after timing and resolves failures as bridge
  failures.

  Encoding runs after all measured work. If `promise` rejects or encoding
  fails, the owner is disposed when the captured epoch is still current.

  Args:
    promise: Promise of bridge evidence to encode.

  Returns:
    A Promise of Transit text for the supplied evidence, or for failed bridge
    evidence when a failure occurs."
  [promise]
  (let [epoch @owner-epoch*]
    (-> promise
        (.then codec/encode-str)
        (.catch (fn [cause]
                  (dispose-if-current! epoch)
                  (t/encode-str (errors/fail-data {:phase "bridge"
                                                   :message (errors/message-of cause "result encoding failed")})))))))

(defn prepare-scene-request
  "Prepares a scene from a Transit request and returns Transit evidence.

  Args:
    request: Transit string accepted by `schema:load-scene-args`.

  Returns:
    A Promise of Transit text. Success contains status ok and `:preparation`
    evidence. Preparation failures contain status failed, phase, message and
    available cause, partial evidence and blocks under top-level `:phases`.
    Encoding failures report the bridge phase with status, phase and message.
    A failure with a top-level stale marker returns status stale."
  [request]
  (encode-evidence (prepare-request request)))

(defn run-prepared
  "Runs one attempt against a prepared scene.

  Args:
    request: Transit `{:preparation-id id :timeout-ms optional-positive-ms}`.

  Returns:
    A Promise of Transit evidence. Success contains status ok, the preparation
    evidence, top-level main and optional reset phase blocks, scene shape count
    and seed, and effective graphics. Failures contain status failed, phase and
    message. Phase failures also include their cause, preparation evidence,
    recorded blocks and available partial data. Invalid requests report the
    invalid-args phase; encoding failures report the bridge phase. A failure
    with a top-level stale marker returns the stale result."
  [request]
  (encode-evidence
   (try
     (let [params (t/decode-str request)]
       (if (sm/validate [:map {:closed true}
                         [:preparation-id some?]
                         [:timeout-ms {:optional true} [:int {:min 1}]]] params)
         (execute-prepared params)
         (js/Promise.resolve (errors/fail-data {:phase "invalid-args"
                                                :message "runPrepared needs a preparation ID"}))))
     (catch :default cause
       (js/Promise.resolve (errors/fail-data {:phase "invalid-args"
                                              :message (errors/message-of cause "invalid Transit request")}))))))

(defn load-scene
  "Prepares a collected case and runs one attempt.

  Args:
    request: Transit string accepted by `prepare-scene-request`.

  Returns:
    A Promise of Transit evidence. On success, `:preparation` holds the
    preparation evidence and top-level `:phases` holds main and any reset. A
    failed preparation places its available blocks at the top level. An
    attempt failure retains the preparation evidence and recorded blocks
    captured for that attempt. A failure with a top-level stale marker returns
    the stale result."
  [request]
  (encode-evidence
   (-> (prepare-request request)
       (.then (fn [{:keys [status preparation] :as evidence}]
                (if (= status "ok")
                  (execute-prepared {:preparation-id (:preparation-id preparation)})
                  evidence))))))

(defn ping
  "Reports that the bridge is callable.

  Returns:
    A JavaScript object with status `\"ok\"`."
  []
  #js {"status" "ok"})

(defn summarize-record
  "Returns a Transit summary of a saved run record.

  Args:
    record-text: Transit text for a supported run record.
    analysis-text: optional Transit map of analysis settings.

  Returns:
    Transit text for the summary.

  Throws:
    When the run or settings cannot be decoded, validated, summarized or
    encoded."
  ([record-text] (summarize-record record-text nil))
  ([record-text analysis-text]
   (codec/encode-str (summary/summarize (result/decode record-text)
                                        (when analysis-text (t/decode-str analysis-text))))))

(defn compare-records
  "Compares two saved runs and returns Transit comparison data.

  Args:
    baseline-text: Transit text for the baseline run.
    candidate-text: Transit text for the candidate run.
    options-text: Transit map of comparison options.

  Returns:
    Transit text for the comparison.

  Throws:
    When a run or the options cannot be decoded, validated, compared or
    encoded."
  [baseline-text candidate-text options-text]
  (result/encode (compare/compare-runs (result/decode baseline-text)
                                       (result/decode candidate-text)
                                       (t/decode-str options-text))))

(defn format-run
  "Returns human-readable text for a saved run record.

  Args:
    record-text: Transit text for a supported run record.

  Returns:
    Text for the run summary.

  Throws:
    When the run cannot be decoded, validated, summarized or formatted."
  [record-text]
  (format/format-run (summary/summarize (result/decode record-text))))

(defn format-comparison
  "Returns human-readable text for a saved comparison record.

  Args:
    record-text: Transit text for a supported comparison record.

  Returns:
    Text for the comparison report.

  Throws:
    When the comparison cannot be decoded, validated or formatted."
  [record-text]
  (format/format-comparison (result/decode record-text)))

(def bridge
  "Browser-side functions exported from the compiled module.

  Keys use the camelCase names used by the runner: `ping`,
  `buildIdentity`, `loadScene`, `prepareScene`, `runPrepared`, `summarize`,
  `compare`, `formatRun`, `formatComparison` and `dispose`. Each entry returns
  Transit text, report text, or, for `ping` and `dispose`, a JavaScript status
  object."
  #js {"ping" ping
       "buildIdentity" #(t/encode-str (identity/data))
       "loadScene" load-scene
       "prepareScene" prepare-scene-request
       "runPrepared" run-prepared
       "summarize" summarize-record
       "compare" compare-records
       "formatRun" format-run
       "formatComparison" format-comparison
       "dispose" dispose!})
