;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.browser.bridge
  "The browser bridge prepares scenes and executes renderer benchmark attempts.

  A preparation owns one module, graphics context, scene snapshot and upload.
  'Warm' attempts are those that reuse that preparation. 'Fresh' preparations
  execute once.

  Setup follows the editor initialization and synchronous object upload paths.
  Node calls the exported bridge through `page.evaluate`.

  Scene, measurement and reports return Transit. Ping and disposal return status
  objects.

  Failures retain their phase, cause and available partial evidence."
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
   [benches.render-wasm.failures :as fail]
   [benches.render-wasm.fingerprint :as fingerprint]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.report.compare :as compare]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [benches.render-wasm.runtime.protocol :as protocol]
   [benches.render-wasm.snapshot :as snapshot]))

;; Forward decls
(declare now guard-current! read-graphics)

(def schema:load-scene-args
  "Decoded Transit request for `load-scene`."
  [:map {:closed true}
   [:case decl/schema:collected-case]
   [:preparation-id {:optional true} some?]
   [:timeout-ms {:optional true} [:int {:min 1}]]
   [:module-url {:optional true} string?]
   [:wasm-url {:optional true} string?]])

(def ^:const ^:private attempt-ms-budget
  "Default wall deadline for setup or one attempt, checked by browser-hooks.
  Requests can supply a timeout. Node's outer timeout covers blocked pages."
  30000)

(defn- browser-hooks
  "Browser hooks for one owner epoch and deadline.

  This somewhat awkward decoupling keeps scene declarations safe to load in
  Node, where we don't have a canvas nor a graphics context. It also lets tests
  control time and renderer responses."
  [epoch deadline-ms]
  {:now               now
   ;; rAF scheduling
   :frame             #(js/Promise.
                        (fn [resolve] (.requestAnimationFrame js/window resolve)))
   :sleep             #(js/Promise. (fn [resolve] (js/setTimeout resolve %)))
   ;; checks that this attempt still owns the page, WebGL context hasn't been
   ;; lost, and the  deadline hasn't passed
   :check             (fn []
                        (when @wasm/context-lost?
                          (throw (ex-info "WebGL context lost"
                                          {:phase "context-lost"})))
                        (guard-current! epoch "protocol")
                        (when (> (now) deadline-ms)
                          (throw (ex-info "attempt deadline exceeded"
                                          {:phase "deadline"}))))
   ;; WASM calls
   :render            (fn [timestamp flags]
                        (h/call wasm/internal-module "_render" timestamp flags))
   :render-from-cache #(h/call wasm/internal-module "_render_from_cache" 0)
   :set-view          #(h/call wasm/internal-module "_set_view"
                               (:scale %) (- (:x %)) (- (:y %)))
   :set-view-start    #(h/call wasm/internal-module "_set_view_start")
   :set-view-end      #(h/call wasm/internal-module "_set_view_end")})



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

(defonce ^:private prepared*
  (atom nil))


(defn- mark-live!
  "Marks the current renderer context as initialized and available."
  []
  (reset! wasm/context-lost? false)
  (set! wasm/context-initialized? true))

(defn- now
  "Reads the browser's monotonic clock in milliseconds."
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
  "Recognizes common software graphics implementations from their renderer text."
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
  (reset! canvas* nil)
  (reset! prepared* nil))

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
                  (unchecked-get attrs k)))
        vendor (try
                 (if-let [ext (.getExtension ^js ctx "WEBGL_debug_renderer_info")]
                   (str (.getParameter ^js ctx (unchecked-get ext "UNMASKED_VENDOR_WEBGL")))
                   "unknown")
                 (catch :default _ "unknown"))]
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
  "Wires context lost/restored handling for `canvas`, epoch-guarded: a stale
  canvas (removed but not yet GC'd) must not kill the live owner. On loss the
  current owner releases its canvas and GL resources itself: the protocol
  loop only observes staleness afterwards, so a cleanup by `render-ok` /
  `warm-ok` there would always miss."
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
  "Maps a chain rejection to the bridge value. Stale work resolves to the
  stale marker without touching the newer owner; any other failure releases
  the owner when it still owns the realm and resolves to a failure map, so
  the bridge promise never rejects. The top message stays phase-labeled for
  accounting; the original failure lives under `:cause` as plain data. Only
  the top-level cause decides staleness."
  [epoch cause fallback-phase]
  (if (fail/stale? cause)
    {:status "stale"}
    (let [phase   (try (or (:phase (ex-data cause))
                           fallback-phase
                           "aborted")
                       (catch :default _
                         (or fallback-phase "aborted")))
          message (try (or (ex-message cause) "unknown failure")
                       (catch :default _
                         "unknown failure"))
          detail  (try (fail/describe-cause cause)
                       (catch :default _
                         [{:message fail/unrenderable-cause-text}]))
          partial (try (:partial (ex-data cause)) (catch :default _ nil))
          preparation (get-in @prepared* [:graphics :preparation])]
      (dispose-if-current! epoch)
      (cond-> (fail/fail-data {:phase   phase
                               :message message
                               :cause   detail})
        partial (assoc :partial partial)
        preparation (assoc :preparation preparation)))))

(defn- guard-current!
  "Throws the stale marker when `epoch` no longer owns the realm. Every
  async step calls it first with its own phase so the terminal handler can
  tell superseded work from genuine failures. The marker lives in `ex-data`,
  never in the message: message text is not a protocol."
  [epoch phase]
  (when-not (= epoch @owner-epoch*)
    (throw (ex-info "superseded" {::fail/stale true :phase phase}))))

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

(defn- case-ok
  "Adds lifecycle and graphics data to one compiled body's measurement."
  [epoch case-desc seed graphics measurement]
  (guard-current! epoch "render")
  (if-not (wasm/live?)
    (do
      (dispose-if-current! epoch)
      (fail/fail-data {:phase (if (= :reuse (:context case-desc))
                                "interact"
                                "first-render")
                       :message "disposed or context lost during case"}))
    (let [{:keys [module-ms graphics-ms snapshot upload-ms preparation]} graphics
          setup {:module-init-ms module-ms :graphics-init-ms graphics-ms :upload-ms upload-ms}]
      (merge measurement
             {:status "ok"
              :preparation preparation
              :metrics (measurement/metrics measurement (= :fresh (:context case-desc)) setup)
              :trace (dissoc measurement :metrics)
              :scene {:shapes (count (:objects snapshot))
                      :seed seed}
              :effective-graphics (:effective-graphics preparation)}))))

(defn- run-case
  "Awaits untimed restore for reuse cases, then calls the compiled body once.
  Its synchronous return is assimilated into the same Promise result path."
  [{:keys [epoch hooks]} case-desc local-case graphics]
  (let [reuse? (= :reuse (:context case-desc))
        prep   (if reuse?
                 (-> (protocol/restore hooks (:view graphics))
                     (.catch (fn [cause]
                               (if (fail/stale? cause)
                                 (throw cause)
                                 (throw (ex-info "restore failed"
                                                 (assoc (ex-data cause) :phase "restore")
                                                 (if (some? (ex-data cause)) (ex-cause cause) cause)))))))
                 (js/Promise.resolve nil))
        rtx    {:case case-desc
                :scene (:snapshot graphics)
                :module wasm/internal-module
                :view (:view graphics)
                :hooks hooks}]
    (-> prep
        (.then (fn [_]
                 (guard-current! epoch "render")
                 (js/Promise.resolve ((:run! local-case) rtx))))
        (.then (fn [measurement]
                 (case-ok epoch case-desc (:scene-seed case-desc)
                          graphics measurement)))
        (.catch (fn [cause]
                  (assoc (terminal-failure epoch cause (if reuse? "interact" "first-render"))
                         :preparation (:preparation graphics)))))))

(defn- parse-load-request
  "Decodes and validates the request before claiming the page. Failure
  leaves the current owner untouched."
  [request]
  (try
    (let [params (t/decode-str request)]
      (if-not (sm/validate schema:load-scene-args params)
        {:error (fail/fail-data {:phase "invalid-args"
                                 :message "load-scene needs a Transit request with case"})}
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
          {:params params
           :case-desc case-desc
           :local-case local
           :scene scene})))
    (catch :default cause
      {:error (fail/fail-data {:phase "invalid-args"
                               :message (or (ex-message cause)
                                            "invalid Transit request")})})))

(defn- begin-load!
  "Claims the page, releases the prior owner and fixes the viewport.
  Module instantiation gets a fresh cache. Preparation supplies the deadline."
  [case-desc]
  (let [epoch (swap! owner-epoch* inc)]
    (release-owner-resources!)
    (reset! module-promise* nil)
    (let [viewport (decl/resolve-viewport (:view case-desc))]
      {:epoch epoch
       :dims viewport})))

(defn- load-module
  "Imports and instantiates the renderer. A failed import or factory call
  keeps the module-init phase and its cause for bridge accounting."
  [epoch params]
  (-> (ensure-module! (or (:module-url params) "./render-wasm.js")
                      (or (:wasm-url params) "./render-wasm.wasm"))
      (.catch (fn [cause]
                (throw (ex-info (str "module init failed: "
                                     (or (ex-message cause) cause))
                                {:phase "module-init"}
                                cause))))
      (.then (fn [installed]
               (guard-current! epoch "module-init")
               installed))))

(defn- initialize-graphics!
  "Creates the canvas and WebGL context, binds them to the renderer and
  returns the module and graphics timings for later phases."
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
  "Builds the registered scene, then fingerprints and orders its shapes before
  the upload timer begins. Scene builders return validated snapshots."
  [epoch scene case-desc seed graphics]
  (guard-current! epoch "scene-build")
  (try
    (let [snapshot ((:build scene) (assoc (:params case-desc) :seed seed))]
      (assoc graphics
             :snapshot snapshot
             :fingerprint (fingerprint/fingerprint snapshot)
             :ordered (snapshot/upload-order snapshot)))
    (catch :default cause
      (if (fail/stale? cause)
        (throw cause)
        (throw (ex-info "scene build failed"
                        {:phase "scene-build"}
                        cause))))))

(defn- upload-scene!
  "Sets the initial view and uploads prepared shapes inside the upload
  timer. Ends the loading lifecycle even when serialization fails."
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
      (if (fail/stale? cause)
        (throw cause)
        (throw (ex-info "upload failed"
                        {:phase "upload"}
                        cause))))))

(defn- preparation-data
  "Projects setup facts without renderer handles or snapshot contents."
  [id case-desc dims graphics]
  (let [{:keys [module-ms graphics-ms upload-ms renderer ctx snapshot fingerprint setup-render]} graphics
        {:keys [width height dpr]} dims]
    {:preparation-id id
     :case-id (:id case-desc)
     :fingerprint fingerprint
     :effective-graphics (read-graphics (:context ctx) width height dpr renderer)
     :diagnostics (cond-> {:module-init-ms module-ms :graphics-init-ms graphics-ms
                           :upload-ms upload-ms
                           :scene {:shapes (count (:objects snapshot)) :seed (:scene-seed case-desc)}}
                    setup-render (assoc :setup-render setup-render))}))

(defn- prepare-request
  "Builds, fingerprints and uploads one owned scene.
  Warm initial rendering stays in diagnostics. No attempt slot runs here."
  [request]
  (try
    (let [{:keys [error params case-desc local-case scene]} (parse-load-request request)]
      (if error
        (js/Promise.resolve error)
        (let [{:keys [epoch dims] :as owner} (begin-load! case-desc)
              timeout-ms (or (:timeout-ms params) attempt-ms-budget)
              owner (assoc owner :hooks (browser-hooks epoch (+ (now) timeout-ms)))
              id (or (:preparation-id params) epoch)
              available* (volatile! {})]
          (-> (load-module epoch params)
              (.then (fn [module] (initialize-graphics! epoch dims module)))
              (.then (fn [graphics]
                       (vreset! available* (select-keys graphics [:module-ms :graphics-ms]))
                       (let [graphics (prepare-scene epoch scene case-desc (:scene-seed case-desc) graphics)]
                         (vswap! available* assoc :fingerprint (:fingerprint graphics))
                         graphics)))
              (.then (fn [graphics] (upload-scene! epoch case-desc graphics)))
              (.then (fn [graphics]
                       (vswap! available* assoc :upload-ms (:upload-ms graphics))
                       (if (= :reuse (:context case-desc))
                         (-> (protocol/restore (:hooks owner) (:view graphics))
                             (.then (fn [trace] (assoc graphics :setup-render trace))))
                         graphics)))
              (.then (fn [graphics]
                       (guard-current! epoch "prepare")
                       (let [preparation (preparation-data id case-desc dims graphics)]
                         (reset! prepared* {:owner owner :case-desc case-desc :local-case local-case
                                            :timeout-ms timeout-ms :busy? false :used? false
                                            :graphics (assoc graphics :preparation preparation)})
                         {:status "ok" :preparation preparation})))
              (.catch (fn [cause]
                        (if (fail/stale? cause)
                          (terminal-failure epoch cause "aborted")
                          (terminal-failure epoch
                                            (ex-info (or (ex-message cause) "setup failed")
                                                     (assoc (ex-data cause) :partial
                                                            (merge {:setup @available*}
                                                                   (:partial (ex-data cause))))
                                                     (if (some? (ex-data cause)) (ex-cause cause) cause))
                                            "aborted"))))))))
    (catch :default cause
      (js/Promise.resolve (fail/fail-data {:phase "setup" :message (ex-message cause)})))))

(defn- execute-prepared
  "Runs one body against the owned preparation with a fresh attempt deadline.
  Fresh preparations run once; warm preparations restore and drain before each
  body without rebuilding, uploading or copying setup metrics into samples."
  [{:keys [preparation-id timeout-ms]}]
  (let [{:keys [owner case-desc local-case graphics busy? used?] :as prepared} @prepared*
        id (get-in graphics [:preparation :preparation-id])]
    (if (or (nil? prepared) (not= id preparation-id) busy?
            (and used? (= :fresh (:context case-desc))))
      (js/Promise.resolve (fail/fail-data {:phase "invalid-args"
                                           :message "preparation unavailable, busy or already used"}))
      (let [epoch (:epoch owner)
            timeout-ms (or timeout-ms (:timeout-ms prepared))
            owner (assoc owner :hooks (browser-hooks epoch (+ (now) timeout-ms)))]
        (swap! prepared* assoc :busy? true :used? true)
        (-> (run-case owner case-desc local-case graphics)
            (.then (fn [evidence]
                     (when (= epoch @owner-epoch*)
                       (swap! prepared* assoc :busy? false))
                     evidence))
            (.catch (fn [cause] (terminal-failure epoch cause "attempt"))))))))

(defn- encode-evidence
  "Encodes bridge evidence after all timing; reports codec errors as failures."
  [promise]
  (let [epoch @owner-epoch*]
    (-> promise
        (.then codec/encode-str)
        (.catch (fn [cause]
                  (dispose-if-current! epoch)
                  (t/encode-str (fail/fail-data {:phase "bridge"
                                                 :message (or (ex-message cause) "result encoding failed")})))))))

(defn prepare-scene-request
  "Accepts a Transit load request and returns a Transit preparation.
  Repeated warm attempts use runPrepared with this preparation's exact ID."
  [request]
  (encode-evidence (prepare-request request)))

(defn run-prepared
  "Accepts Transit {:preparation-id id :timeout-ms optional-positive-ms}.
  Invalid requests leave the owner untouched. Evidence resolves as Transit."
  [request]
  (encode-evidence
   (try
     (let [params (t/decode-str request)]
       (if (sm/validate [:map {:closed true}
                         [:preparation-id some?]
                         [:timeout-ms {:optional true} [:int {:min 1}]]] params)
         (execute-prepared params)
         (js/Promise.resolve (fail/fail-data {:phase "invalid-args"
                                              :message "runPrepared needs a preparation ID"}))))
     (catch :default cause
       (js/Promise.resolve (fail/fail-data {:phase "invalid-args"
                                            :message (or (ex-message cause) "invalid Transit request")}))))))

(defn load-scene
  "Prepares a collected case and runs one attempt, returning Transit evidence.
  Fresh render timing starts after upload. Warm setup stays diagnostic;
  prepareScene/runPrepared let a serial runner reuse the owned warm scene."
  [request]
  (encode-evidence
   (-> (prepare-request request)
       (.then (fn [{:keys [status preparation] :as evidence}]
                (if (= status "ok")
                  (execute-prepared {:preparation-id (:preparation-id preparation)})
                  evidence))))))

(defn ping
  "Bridge placeholder that checks the Node/browser boundary."
  []
  #js {"status" "ok"})

(defn summarize-record
  "Returns a Transit summary of a raw Transit record."
  ([record-text] (summarize-record record-text nil))
  ([record-text analysis-text]
   (codec/encode-str (summary/summarize (result/decode record-text)
                                        (when analysis-text (t/decode-str analysis-text))))))

(defn compare-records
  "Returns Comparison schema2 Transit for two saved runs and Transit options."
  [baseline-text candidate-text options-text]
  (result/encode (compare/compare-runs (result/decode baseline-text)
                                       (result/decode candidate-text)
                                       (t/decode-str options-text))))

(defn format-run
  "Returns text for a summary computed from a raw Transit run."
  [record-text]
  (format/format-run (summary/summarize (result/decode record-text))))

(defn format-comparison
  "Returns text for a Comparison schema2 Transit record."
  [record-text]
  (format/format-comparison (result/decode record-text)))

(def bridge
  "Browser-side bridge exported from the compiled module."
  #js {"ping" ping
       "loadScene" load-scene
       "prepareScene" prepare-scene-request
       "runPrepared" run-prepared
       "summarize" summarize-record
       "compare" compare-records
       "formatRun" format-run
       "formatComparison" format-comparison
       "dispose" dispose!})
