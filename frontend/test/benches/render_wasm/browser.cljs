;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.browser
  "Browser bridge for the benchmarks.

  Lifecycle here mirrors the editor cold-load path
  (`app.render-wasm.api/initialize-viewport` + `set-objects` sync).

  The browser exports `bridge`; Node passes data through `page.evaluate`.

  `loadScene` resolves to Transit. `ping` and `dispose` return plain data.

  Failures are returned as `{\"status\" \"failed\" ...}` maps."
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
   [benches.render-wasm.protocol :as protocol]
   [benches.render-wasm.scenes.common :as scenes]
   [benches.render-wasm.scenes.core :as core]
   [benches.render-wasm.upload :as upload]))

;; Forward decls
(declare now guard-current! read-graphics)

(def schema:load-scene-args
  "Decoded Transit request for `load-scene`."
  [:map {:closed true}
   [:seed [:int {:min 0 :max 4294967295}]] ;; derived in `collect-cases`
   [:case map?]
   [:module-url {:optional true} string?]
   [:wasm-url {:optional true} string?]])

(def ^:const ^:private attempt-ms-budget
  "Own wall deadline for one bridge attempt, checked by `browser-hooks`.
  Ticket 11 accepts a per-attempt timeout; until then this fixed value
  bounds runaway drains. The Node outer timeout stays the backstop for
  synchronously blocked pages."
  30000)

(def ^:const ^:private gesture-settle-ms
  "Declared settle wait for warm gestures. The actual wait is recorded
  separately by `protocol/interact`."
  100)

(defn- browser-hooks
  "Browser hooks for one owner epoch and deadline."
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


;; TODO(tickets 07/08): Move gestures into scene cases. These rectangle ID
;; checks reject every new warm case; the bridge should run its gesture.
(defn- linear-frames
  "Linear interpolation of `{:scale :x :y}` views from `from` to `to` over
  `n` frames."
  [from to n]
  (mapv (fn [i]
          (let [t (/ (inc i) n)]
            {:scale (+ (:scale from) (* t (- (:scale to) (:scale from))))
             :x     (+ (:x from) (* t (- (:x to) (:x from))))
             :y     (+ (:y from) (* t (- (:y to) (:y from))))}))
        (range n)))

(defn- gesture-frames
  "Builds rectangle pan or zoom frames from the case view. Other case ids
  throw because the bridge has no gesture for them."
  [case view]
  (cond
    (= (:id case) :rects/pan)
    (linear-frames view (update view :x + 200) 20)

    (= (:id case) :rects/zoom)
    (linear-frames view (update view :scale * 1.5) 20)

    :else
    (throw (ex-info (str "no gesture declared for case " (:id case))
                    {:phase "gesture"
                     :case  (:id case)}))))

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
  (assoc m :status "failed"))

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

(defn- render-ok
  "Maps one drain result to the bridge value. Success keeps the owner live
  for explicit disposal. Failures release the owner only if it still is current
  (i.e. it owns the page resources) so a stale failure cannot dispose of
  resources belonging to a newer owner."
  [epoch module-ms graphics-ms upload-ms alive? drain-result snapshot seed ctx width height dpr renderer]
  (if-not alive?
    (do
      (dispose-if-current! epoch)
      (fail-data {:phase "first-render"
                  :message "disposed or context lost during render"}))
    (assoc drain-result
           :status "ok"
           :module-init-ms module-ms
           :graphics-init-ms graphics-ms
           :upload-ms upload-ms
           :first-render-ms (:full-ms drain-result)
           :render-frames (count (:slices drain-result))
           :scene {:shapes (count (:objects snapshot))
                   :seed seed}
           :effective-graphics (read-graphics ctx width height dpr renderer))))

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
  "True for numbers kept as numbers in failure causes. NaN and
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
    {:status "stale"}
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

(defn- warm-ok
  "Maps an interaction to the bridge result. Success keeps the owner live;
  failure releases it if this attempt still owns the page."
  [epoch module-ms graphics-ms upload-ms interact-ms alive? interaction snapshot seed ctx width height dpr renderer]
  (if-not alive?
    (do
      (dispose-if-current! epoch)
      (fail-data {:phase "interact"
                  :message "disposed or context lost during interaction"}))
    (merge interaction
           {:status "ok"
            :module-init-ms module-ms
            :graphics-init-ms graphics-ms
            :upload-ms upload-ms
            :interact-ms interact-ms
            :scene {:shapes (count (:objects snapshot))
                    :seed seed}
            :effective-graphics (read-graphics ctx width height dpr renderer)})))

(defn- run-fresh
  "Times one drain for a fresh-context case. Resolves the bridge value
  through `render-ok`; drain failures resolve through `terminal-failure`."
  [epoch hooks seed dims g live?]
  (let [{:keys [module-ms graphics-ms renderer ctx snapshot upload-ms]} g
        {:keys [width height dpr]} dims]
    (-> (protocol/drain hooks {:flags 0 :origin ((:now hooks)) :immediate false})
        (.then (fn [drain-result]
                 (render-ok epoch module-ms graphics-ms upload-ms
                            (live?) drain-result snapshot seed
                            (:context ctx) width height dpr renderer)))
        (.catch (fn [cause]
                  (terminal-failure epoch cause "first-render"))))))

(defn- run-warm
  "Restores the initial view and drains outside timing, then times one
  gesture for a reuse-context case. Resolves the bridge value through
  `warm-ok`; gesture failures resolve through `terminal-failure`."
  [epoch hooks case-desc seed dims g live?]
  (let [{:keys [module-ms graphics-ms renderer ctx snapshot upload-ms view]} g
        {:keys [width height dpr]} dims]
    (-> (protocol/restore hooks view)
        (.then (fn [_]
                 (guard-current! epoch "interact")
                 (let [t0 ((:now hooks))]
                   (-> (protocol/interact
                        hooks
                        {:frames (gesture-frames case-desc view)
                         :settle-ms gesture-settle-ms})
                       (.then (fn [interaction]
                                (warm-ok epoch module-ms graphics-ms upload-ms
                                         (- ((:now hooks)) t0) (live?) interaction
                                         snapshot seed (:context ctx)
                                         width height dpr renderer)))
                       (.catch (fn [cause]
                                 (terminal-failure epoch cause "interact")))))))
        (.catch (fn [cause]
                  (terminal-failure epoch cause "restore"))))))

(defn- parse-load-request
  "Decodes and validates the request before claiming the page. Failure
  leaves the current owner untouched."
  [request]
  (try
    (let [params (t/decode-str request)]
      (if-not (sm/validate schema:load-scene-args params)
        {:error (fail-data {:phase "invalid-args"
                            :message "load-scene needs a Transit request with seed and case"})}
        (let [case-desc (:case params)
              scene     (core/registered-scene (:scene case-desc))]
          (when (nil? scene)
            (throw (ex-info (str "unknown scene: " (:scene case-desc))
                            {:phase "invalid-args"})))
          {:params params
           :case-desc (core/check-collected-case case-desc)
           :scene scene})))
    (catch :default cause
      {:error (fail-data {:phase "invalid-args"
                          :message (or (ex-message cause)
                                       "invalid Transit request")})})))

(defn- begin-load!
  "Claims the page, releases the prior owner, then fixes this attempt's
  viewport, hooks and deadline. Module instantiation gets a fresh cache."
  [case-desc]
  (let [epoch (swap! owner-epoch* inc)]
    (release-owner-resources!)
    (reset! module-promise* nil)
    (let [viewport (core/resolve-viewport (:view case-desc))]
      {:epoch epoch
       :dims viewport
       :hooks (browser-hooks epoch (+ (now) attempt-ms-budget))
       :live? (fn [] (and (= epoch @owner-epoch*) (wasm/live?)))})))

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
  "Builds and validates the registered scene, then orders its shapes before
  the upload timer begins."
  [epoch scene case-desc seed graphics]
  (guard-current! epoch "scene-build")
  (try
    (let [snapshot (-> ((:build scene) (assoc (:params case-desc) :seed seed))
                       (scenes/validate!))]
      (assoc graphics
             :snapshot snapshot
             :ordered (upload/prepare-scene snapshot)))
    (catch :default cause
      (if (stale? cause)
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
        ;; Rects carry no layout; per-scene upload options arrive with 07/08.
        (serialize-shape/serialize-shapes-batch
         (:ordered graphics)
         {:include-layout? false
          :include-fills-strokes? true})
        (finally
          (h/call wasm/internal-module "_end_loading")))
      (h/call wasm/internal-module "_set_view_end")
      (assoc graphics :upload-ms (- (now) u0) :view view))
    (catch :default cause
      (if (stale? cause)
        (throw cause)
        (throw (ex-info "upload failed"
                        {:phase "upload"}
                        cause))))))

;; TODO(ticket 14): Run the declared case body here. Context dispatch skips
;; `:run!`, so custom cases cannot execute through this bridge.
(defn- render-scene
  "Runs the fresh or reuse completion path after upload."
  [{:keys [epoch dims hooks live?]} case-desc seed graphics]
  (guard-current! epoch "render")
  (if (= (:context case-desc) :fresh)
    (run-fresh epoch hooks seed dims graphics live?)
    (run-warm epoch hooks case-desc seed dims graphics live?)))

(defn load-scene
  "Loads one collected case to Full. Takes a Transit request with `:seed`,
  `:case` and optional module/WASM URLs; resolves to a Transit result.
  Validation precedes ownership and all timers. Fresh cases time upload
  and one drain; reuse cases restore and drain outside interaction timing."
  [request]
  (let [epoch* (volatile! nil)
        result (try
                 (let [{:keys [error params case-desc scene]}
                       (parse-load-request request)
                       seed (:seed params)]
                   (if error
                     (js/Promise.resolve error)
                     (let [{:keys [epoch dims] :as attempt} (begin-load! case-desc)]
                       (vreset! epoch* epoch)
                       (-> (load-module epoch params)
                           (.then (fn [module]
                                    (initialize-graphics! epoch dims module)))
                           (.then (fn [graphics]
                                    (prepare-scene epoch scene case-desc seed graphics)))
                           (.then (fn [graphics]
                                    (upload-scene! epoch case-desc graphics)))
                           (.then (fn [graphics]
                                    (render-scene attempt case-desc seed graphics)))
                           (.catch (fn [cause]
                                     (terminal-failure epoch cause "aborted")))))))
                 (catch :default cause
                   (js/Promise.resolve
                    (fail-data {:phase "setup" :message (ex-message cause)}))))]
    (-> result
        (.then t/encode-str)
        (.catch (fn [cause]
                  (when-some [epoch @epoch*]
                    (dispose-if-current! epoch))
                  (t/encode-str
                   (fail-data {:phase "bridge"
                               :message (or (ex-message cause)
                                            "result encoding failed")})))))))

(defn ping
  "Bridge placeholder that checks the Node/browser boundary."
  []
  #js {"status" "ok"})

(def bridge
  "Browser-side bridge exported from the compiled module."
  #js {"ping" ping
       "loadScene" load-scene
       "dispose" dispose!})
