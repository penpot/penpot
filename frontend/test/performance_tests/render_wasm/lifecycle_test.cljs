;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.lifecycle-test
  "Tests the browser bridge with fake DOM and renderer boundaries.

  It covers bridge input, ownership, disposal and `load-scene` with fake
  canvases and a fake Emscripten factory. Progressive frames use an optional
  clock at the browser boundary."
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [app.common.transit :as transit]
   [benches.render-wasm.browser.bridge :as browser]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.declarations :as decl]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [benches.render-wasm.runtime.camera :as camera]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

;; Forward declaration: entry-test helpers below build args against the
;; fake Emscripten factory URL defined alongside the fake-DOM harness.
(declare factory-url)

;; Partial-init unwinding of `init-context` is covered in
;; `frontend-tests.render-wasm.webgl-test`, which also pins the success,
;; nil-context and registration-failure cases.

(defn- register-phase-case
  [options run]
  (let [params (:params (decl/registered-case :rects/load))]
    (decl/defcase :rects/lifecycle
      (-> (merge {:params params
                  :view {:scale 1 :x 0 :y 0}
                  :context :fresh}
                 options)
          (update :params #(merge params %)))
      [rtx]
      (run rtx))))

(defn- load-case
  "Returns the collected rectangle case for entry tests."
  []
  (first (cases/collect-cases {:master-seed 42
                               :filter (if (decl/registered-case :rects/lifecycle)
                                         "rects/lifecycle"
                                         "rects/load")})))

(defn- scene-args
  "Transit request for `browser/load-scene`. `overrides` change the
  collected case descriptor."
  [seed overrides]
  (let [base (or (some #(when (= (:id %) (:id overrides)) %)
                       (cases/collect-cases {:master-seed 42}))
                 (load-case))]
    (transit/encode-str
     {:case (merge base {:scene-seed seed} overrides)
      :module-url (factory-url)
      :wasm-url "./fake.wasm"})))

(defn- expect-failed-args
  "Calls `load-scene` with `args` (never touching DOM/FFI: validation runs
  first) and asserts a failure map. Calls `done` when finished."
  [args check-phase? done]
  (-> (.then (browser/load-scene args)
             (fn [result]
               (let [m (transit/decode-str result)]
                 (t/is (= "failed" (:status m)))
                 (when check-phase?
                   (t/is (= "invalid-args" (:phase m))))
                 (done))))
      (.catch (fn [cause]
                (t/is false (str "must resolve, threw: " cause))
                (done)))))

(t/deftest invalid-seed-fails-before-timers
  (t/async done
    (expect-failed-args (scene-args -1 {}) true done)))

(t/deftest missing-seed-fails-before-timers
  (t/async done
    (expect-failed-args (transit/encode-str {:case (dissoc (load-case) :scene-seed)}) true done)))

(t/deftest missing-case-fails-before-timers
  (t/async done
    (expect-failed-args (transit/encode-str {}) true done)))

(t/deftest malformed-transit-fails-before-timers
  (t/async done
    (expect-failed-args "not transit" true done)))

(t/deftest invalid-view-fails-before-timers
  (t/async done
    (expect-failed-args
     (scene-args 42 {:view {:scale -1 :x 0 :y 0}})
     true
     done)))

(t/deftest invalid-viewport-fails-before-timers
  (t/async done
    (expect-failed-args
     (scene-args 42 {:view {:scale 1 :x 0 :y 0
                            :viewport {:width -5}}})
     true
     done)))

(t/deftest missing-compiled-body-fails-before-claim
  (t/async done
    (expect-failed-args (scene-args 42 {:id :rects/missing}) true done)))

(t/deftest ^:async wrong-scene-body-rejects-the-load-request
  (let [id :rects/wrong-scene
        descriptor (load-case)]
    (decl/register-case! {:id id :scene :other :ns "lifecycle-test"
                          :run! (fn [_] nil)})
    (try
      (let [request (transit/encode-str {:case (assoc descriptor :id id)})
            result  (transit/decode-str (await (browser/prepare-scene-request request)))]
        (t/is (= "failed" (:status result)))
        (t/is (= "invalid-args" (:phase result))))
      (finally
        (decl/unregister-case! id)))))

(t/deftest non-finite-view-values-fail-before-timers
  (t/async done
    (expect-failed-args (scene-args 42 {:view {:scale js/Infinity :x 0 :y 0}})
                        true
                        (fn []
                          (expect-failed-args (scene-args 42 {:view {:scale 1 :x js/NaN :y 0}})
                                              true
                                              (fn []
                                                (expect-failed-args (scene-args 42 {:view {:scale 1 :x 0 :y js/Infinity}})
                                                                    true
                                                                    done)))))))

(t/deftest viewport-defaults-apply-at-use
  (t/is (= {:width 1920 :height 1080 :dpr 2}
           (decl/resolve-viewport {:scale 1 :x 0 :y 0})))
  (t/is (= {:width 800 :height 600 :dpr 2}
           (decl/resolve-viewport {:scale 1 :x 0 :y 0
                                   :viewport {:width 800 :height 600}}))))

(t/deftest dispose-resets-lifecycle-state
  (set! wasm/internal-module #js {})
  (set! wasm/context-initialized? true)
  (reset! wasm/context-lost? true)
  (set! wasm/gl-context-handle 7)
  (set! wasm/gl-context #js {})
  (set! wasm/canvas #js {})
  (let [removed (atom false)
        fake    #js {:remove (fn [] (reset! removed true))}]
    (reset! @#'browser/canvas* fake)
    (let [result (browser/dispose!)]
      (t/is (= "disposed" (unchecked-get result "status")))
      (t/is (nil? wasm/internal-module))
      (t/is (false? wasm/context-initialized?))
      (t/is (false? @wasm/context-lost?))
      (t/is (nil? wasm/gl-context-handle))
      (t/is (nil? wasm/gl-context))
      (t/is (nil? wasm/canvas))
      (t/is (nil? @@#'browser/canvas*))
      (t/is (true? @removed) "canvas is removed from the DOM")))
  (t/testing "double dispose is idempotent"
    (let [result (browser/dispose!)]
      (t/is (= "disposed" (unchecked-get result "status")))
      (t/is (nil? wasm/internal-module))))
  (t/testing "dispose bumps the owner epoch"
    (let [before @@#'browser/owner-epoch*]
      (browser/dispose!)
      (t/is (< before @@#'browser/owner-epoch*)))))

(t/deftest stale-work-never-disposes-the-newer-owner
  (let [guard     @#'browser/guard-current!
        terminate @#'browser/terminal-failure
        epoch     @@#'browser/owner-epoch*]
    (t/testing "guard passes for the current owner"
      (t/is (nil? (guard epoch "module-init"))))
    (t/testing "guard throws stale once superseded"
      (browser/dispose!)
      (let [thrown (try
                     (guard epoch "module-init")
                     nil
                     (catch :default cause
                       cause))]
        (t/is (errors/stale? thrown)
              "the marker lives in ex-data, never in message text")))
    (t/testing "terminal stale resolves without disposal"
      (let [before @@#'browser/owner-epoch*
            result (terminate epoch
                              (ex-info "superseded"
                                       {::errors/stale true
                                        :phase "upload"})
                              "upload")
            after  @@#'browser/owner-epoch*]
        (t/is (= "stale" (:status result)))
        (t/is (= before after) "no epoch bump: the newer owner is untouched")))
    (t/testing "terminal failure keeps its phase and disposes the current owner"
      (let [current @@#'browser/owner-epoch*
            result  (terminate current (ex-info "upload failed" {:phase "upload"}) "aborted")]
        (t/is (= "failed" (:status result)))
        (t/is (= "upload" (:phase result)))
        (t/is (< current @@#'browser/owner-epoch*) "current owner disposed")))
    (t/testing "phaseless failures use the fallback phase"
      (set! wasm/internal-module nil)
      (let [current @@#'browser/owner-epoch*
            result  (terminate current (ex-info "boom" {}) "aborted")]
        (t/is (= "failed" (:status result)))
        (t/is (= "aborted" (:phase result)))
        (t/is (< current @@#'browser/owner-epoch*) "current owner disposed")))))

(t/deftest bridge-exposes-executor-entries
  (doseq [key ["ping" "loadScene" "prepareScene" "runPrepared" "dispose"]]
    (t/is (fn? (unchecked-get browser/bridge key))
          (str key " is callable through the browser ABI")))
  (t/is (= "ok" (unchecked-get (.ping browser/bridge) "status"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Through-entry fake harness.
;;
;; The DOM/module boundary the fakes cover does not change with the entry:
;; fake DOM, fake Emscripten factory, call recording.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- record!
  "Appends `event` (a vector) to the shared call log."
  [event]
  (.push (unchecked-get js/globalThis "__benchCalls") (clj->js event)))

(defn- fake-canvas
  "Numbered fake canvas identified by `id`. Removal and context loss are
  recorded in the shared call log. The context reports a drawing-buffer size
  distinct from any canvas size, so tests prove `read-graphics` reads it from
  the context rather than the canvas attributes."
  ([id]
   (fake-canvas id nil))
  ([id {:keys [vendor renderer]}]
   (let [debug-ext (when (and vendor renderer)
                     #js {:UNMASKED_VENDOR_WEBGL 1 :UNMASKED_RENDERER_WEBGL 2})
         lose-ext #js {:loseContext (fn [] (record! ["lose" id]) nil)}
         context  #js {:getExtension (fn [name]
                                       (case name
                                         "WEBGL_lose_context" lose-ext
                                         "WEBGL_debug_renderer_info" debug-ext
                                         nil))
                       :getParameter (fn [parameter] (case parameter 1 vendor 2 renderer nil))
                       :getContextAttributes (fn []
                                               #js {"antialias" false
                                                    "depth" true
                                                    "stencil" false
                                                    "alpha" false
                                                    "preserveDrawingBuffer" false})
                       :drawingBufferWidth 1234
                       :drawingBufferHeight 5678}]
     #js {:id id
          :width 0
          :height 0
          :style #js {}
          :getContext (fn [_ _] context)
          :addEventListener (fn [_ _] nil)
          :remove (fn [] (record! ["remove" id]) nil)})))

(defn- install-fake-dom!
  "Installs fake DOM, import, clock and scenario globals and returns a
  `restore!` thunk that restores their previous values. Created canvases are
  numbered from 1 per installation.
  The import polyfill mirrors
  `resources/polyfills/dynamicImport.js`, which ships with the app HTML but
  not with the unit-test bundle."
  []
  (let [prev-document    (unchecked-get js/globalThis "document")
        prev-window      (unchecked-get js/globalThis "window")
        prev-import      (unchecked-get js/globalThis "dynamicImport")
        prev-performance (unchecked-get js/globalThis "performance")
        prev-now-fn      (unchecked-get prev-performance "now")
        prev-now-desc    (js/Object.getOwnPropertyDescriptor prev-performance "now")
        prev-now         (unchecked-get js/globalThis "__benchNow")
        prev-script      (unchecked-get js/globalThis "__benchRenderScript")
        prev-calls       (unchecked-get js/globalThis "__benchCalls")
        prev-factory     (unchecked-get js/globalThis "__benchFactoryCalls")
        prev-throw       (unchecked-get js/globalThis "__benchThrowIn")
        prev-gate        (unchecked-get js/globalThis "__benchGateRaf")
        prev-raf         (unchecked-get js/globalThis "__benchRafCb")
        next-id          (atom 0)
        document         #js {:createElement (fn [_]
                                               (let [id (swap! next-id inc)]
                                                 (record! ["create" id])
                                                 (fake-canvas id)))
                              :body #js {:appendChild (fn [canvas]
                                                        (record! ["append" (unchecked-get canvas "id")])
                                                        canvas)}
                              :querySelectorAll (fn [_] #js [])}
        window           #js {:requestAnimationFrame
                              (fn [cb]
                                ;; Park the first frame while `__benchGateRaf`
                                ;; is set so a second load can supersede a live,
                                ;; active owner; later frames run synchronously.
                                (if (and (unchecked-get js/globalThis "__benchGateRaf")
                                         (nil? (unchecked-get js/globalThis "__benchRafCb")))
                                  (unchecked-set js/globalThis "__benchRafCb" cb)
                                  (if-some [clock (unchecked-get js/globalThis "__benchNow")]
                                    (let [timestamp (+ clock 16)]
                                      (unchecked-set js/globalThis "__benchNow" timestamp)
                                      (cb timestamp))
                                    (cb 0)))
                                nil)}
        fake-now         (fn []
                           (if-some [clock (unchecked-get js/globalThis "__benchNow")]
                             clock
                             (.call prev-now-fn prev-performance)))
        dynamic-import   (fn [url] (js/eval (str "import(" (pr-str url) ")")))]
    (unchecked-set js/globalThis "document" document)
    (unchecked-set js/globalThis "window" window)
    ;; Patch `now` on the real object: shadow hangs the compiled
    ;; `performance.*` namespaces off the global `performance` object, so
    ;; replacing that global hides every `benches.render-wasm.*` var.
    (js/Object.defineProperty prev-performance "now"
                              #js {:value fake-now :configurable true :writable true})
    (unchecked-set js/globalThis "dynamicImport" dynamic-import)
    (fn restore! []
      (unchecked-set js/globalThis "document" prev-document)
      (unchecked-set js/globalThis "window" prev-window)
      (if (undefined? prev-now-desc)
        (js-delete prev-performance "now")
        (js/Object.defineProperty prev-performance "now" prev-now-desc))
      (unchecked-set js/globalThis "__benchNow" prev-now)
      (unchecked-set js/globalThis "__benchRenderScript" prev-script)
      (unchecked-set js/globalThis "__benchCalls" prev-calls)
      (unchecked-set js/globalThis "__benchFactoryCalls" prev-factory)
      (unchecked-set js/globalThis "__benchThrowIn" prev-throw)
      (unchecked-set js/globalThis "__benchGateRaf" prev-gate)
      (unchecked-set js/globalThis "__benchRafCb" prev-raf)
      (unchecked-set js/globalThis "dynamicImport" prev-import))))

(def ^:private factory-source
  "ES module stubbing the Emscripten factory for entry tests. Records every
  renderer call in `globalThis.__benchCalls`; `globalThis.__benchThrowIn`
  names one module fn that throws. `globalThis.__benchRenderScript` holds
  optional [frame-type, duration-ms] pairs that advance `__benchNow`; absent
  a script, `_render` immediately returns Full. The instance trap answers
  `undefined` for `then` and non-strings: promise assimilation would treat the
  instance as a never-settling thenable and hang the load. A real 16 MiB
  heap with bump allocation backs `_alloc_bytes`/`HEAPU8`, so the real batch
  upload assembles bytes instead of throwing on stub returns."
  (str "export default function (opts) {"
       "globalThis.__benchFactoryCalls.push(opts || null);"
       "var rec = globalThis.__benchCalls;"
       "function push(e) { rec.push(e); }"
       "var GL = {"
       "registerContext: function () { push(['register']); return 7; },"
       "makeContextCurrent: function () {},"
       "deleteContext: function (h) { push(['delete', h]); }"
       "};"
       "var heapBuf = new ArrayBuffer(16777216);"
       "var heapNext = 1024;"
       "var base = {"
       "GL: GL,"
       "HEAPU8: new Uint8Array(heapBuf),"
       "HEAPU32: new Uint32Array(heapBuf),"
       "HEAP32: new Int32Array(heapBuf),"
       "HEAPF32: new Float32Array(heapBuf),"
       "_alloc_bytes: function (size) { var p = heapNext; heapNext += ((size + 3) & ~3); return p; },"
       "_free_bytes: function () {},"
       "_read_error_code: function () { return 0; },"
       "_render: function (timestamp, flags) {"
       "push(['render', timestamp, flags]);"
       "var script = globalThis.__benchRenderScript;"
       "if (script && script.length) {"
       "var next = script.shift(); globalThis.__benchNow += next[1]; return next[0];"
       "}"
       "return 2;"
       "},"
       "_clean_up: function () { push(['clean']); return 0; }"
       "};"
       "var inst = new Proxy(base, {"
       "get: function (t, p) {"
       "if (typeof p !== 'string') return undefined;"
       "if (p === 'then') return undefined;"
       "if (p in t) return t[p];"
       "return function () {"
       "push(['call', p]);"
       "if (globalThis.__benchThrowIn === p) throw new Error('fake ' + p + ' failure');"
       "return 0;"
       "};"
       "}"
       "});"
       "return Promise.resolve(inst);"
       "}"))

(defn- factory-url
  "Data-URL Emscripten factory for entry tests. Fully hermetic: no
  filesystem or network access."
  []
  (str "data:text/javascript," (js/encodeURIComponent factory-source)))

(defn- reset-bench-globals!
  "Resets the recording and scenario flags the fake factory reads."
  []
  (unchecked-set js/globalThis "__benchCalls" #js [])
  (unchecked-set js/globalThis "__benchFactoryCalls" #js [])
  (unchecked-set js/globalThis "__benchThrowIn" nil)
  (unchecked-set js/globalThis "__benchGateRaf" false)
  (unchecked-set js/globalThis "__benchRafCb" nil)
  (unchecked-set js/globalThis "__benchNow" nil)
  (unchecked-set js/globalThis "__benchRenderScript" nil))

(defn- read-calls
  "Call log as Clojure data."
  []
  (js->clj (unchecked-get js/globalThis "__benchCalls")))

(defn- effect-count
  "Counts recorded effects without filtering any returned evidence."
  [calls effect]
  (count (filter #(= effect (first %)) calls)))

(defn- load-result
  "Decodes the bridge's Transit result for `k`. A rejected bridge arrives
  as a `threw` map so the test fails with the cause attached."
  [args k]
  (-> (browser/load-scene args)
      (.then (fn [result]
               (t/is (string? result) "loadScene returns Transit")
               (k (transit/decode-str result))))
      (.catch (fn [cause] (k {:status "threw" :cause (str cause)})))))

(defn- with-entry-env
  "Installs the fake DOM, resets bench globals, then calls `f` with a
  `cleanup` thunk that disposes the owner and restores the previous globals.
  Call `cleanup` after asserting, before `done`."
  [f]
  (let [restore-dom! (install-fake-dom!)
        cleanup      (fn []
                       (try
                         (browser/dispose!)
                         (finally
                           (decl/unregister-case! :rects/lifecycle)
                           (restore-dom!))))]
    (reset-bench-globals!)
    (try
      (register-phase-case {} camera/render-sync*)
      (f cleanup)
      (catch :default cause
        (cleanup)
        (throw cause)))))

(t/deftest ^:async preparations-record-the-context-identity-and-software-status
  (doseq [[identity software] [[{:vendor "NVIDIA" :renderer "ANGLE (NVIDIA, Vulkan)"} false]
                               [{:vendor "Google" :renderer "ANGLE (SwiftShader)"} true]
                               [{:vendor "Mesa" :renderer "llvmpipe (LLVM)"} true]
                               [nil false]]]
    (await
     (with-entry-env
       (^:async fn [cleanup]
         (try
           (set! (.-createElement js/document) (fn [_] (fake-canvas 1 identity)))
           (let [response (transit/decode-str (await (browser/prepare-scene-request (scene-args 7 {}))))
                 graphics (get-in response [:preparation :effective-graphics])]
             (t/is (= "ok" (:status response)))
             (t/is (= (or (:vendor identity) "unknown") (:vendor graphics)))
             (t/is (= (or (:renderer identity) "unknown") (:renderer graphics)))
             (t/is (= software (:software graphics))))
           (finally
             (cleanup))))))))

(t/deftest successful-load-reaches-full
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (load-result (scene-args 7 {})
                     (fn [m]
                       (t/is (= "ok" (:status m)))
                       (t/is (= 1 (count (get-in m [:preparation :diagnostics :setup-render :slices]))))
                       (t/is (= [::camera/render-sync]
                                (mapv :command (get-in m [:phases 0 :events]))))
                       (t/is (= 1001 (:shapes (:scene m))) "the canonical scene uploads whole")
                       (let [graphics (:effective-graphics m)]
                         (t/is (= 1234 (:drawing-buffer-width graphics))
                               "drawing buffer width comes from the context")
                         (t/is (= 5678 (:drawing-buffer-height graphics))
                               "drawing buffer height comes from the context")
                         (t/is (false? (:antialias graphics))
                               "context attributes pass through")
                         (t/is (true? (:depth graphics))
                               "context attributes pass through"))
                       (let [calls (read-calls)]
                         (t/is (= 1 (effect-count calls "create")) "one canvas per load")
                         (t/is (zero? (effect-count calls "remove")) "success keeps the owner live"))
                       (cleanup)
                       (done)))))))

(t/deftest preparation-retains-progressive-frame-evidence
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (unchecked-set js/globalThis "__benchNow" 0)
        (unchecked-set js/globalThis "__benchRenderScript"
                       #js [#js [wasm/FRAME_TYPE_PARTIAL 2]
                            #js [wasm/FRAME_TYPE_VIEWPORT_READY 3]
                            #js [wasm/FRAME_TYPE_FULL 5]])
        (-> (.loadScene browser/bridge (scene-args 7 {}))
            (.then (fn [text]
                     (t/is (string? text) "the exported loadScene returns Transit")
                     (let [{:keys [status preparation scene]} (transit/decode-str text)
                           {:keys [slices viewport-ready-ms full-ms]}
                           (get-in preparation [:diagnostics :setup-render])
                           retained     slices
                           render-calls (filterv #(= "render" (first %)) (read-calls))]
                       (t/is (= "ok" status))
                       (t/is (= {:shapes 1001 :seed 7} scene)
                             "the real canonical case prepares and uploads the whole scene")
                       (t/is (= 3 (count retained)) "all three slices cross Transit")
                       (t/is (= [wasm/FRAME_TYPE_PARTIAL
                                 wasm/FRAME_TYPE_VIEWPORT_READY
                                 wasm/FRAME_TYPE_FULL]
                                (mapv :frame-type retained)))
                       (t/is (= [0 wasm/FRAME_TYPE_PARTIAL wasm/FRAME_TYPE_VIEWPORT_READY]
                                (mapv :flags retained))
                             "ViewportReady becomes the next flags and the drain continues to Full")
                       (t/is (= [16 34 53] (mapv :timestamp retained))
                             "each continuation waits for the next frame")
                       (t/is (= [2 3 5] (mapv :duration-ms retained)))
                       (t/is (= render-calls
                                (mapv (fn [{:keys [timestamp flags]}]
                                        ["render" timestamp flags])
                                      retained))
                             "decoded slices retain every WASM call's timestamp and flags in order")
                       (t/is (= 37 viewport-ready-ms
                                (+ (:timestamp (nth retained 1))
                                   (:duration-ms (nth retained 1))))
                             "viewport completion ends with the ViewportReady slice")
                       (t/is (= 58 full-ms
                                (+ (:timestamp (nth retained 2))
                                   (:duration-ms (nth retained 2))))
                             "Full completion ends with the final slice")
                       (t/is (< viewport-ready-ms full-ms))
                       (t/is (= [{:phase :preparation
                                  :preparation-id (:preparation-id preparation)
                                  :outcome :completed :events []}]
                                (:phases preparation))))
                     (cleanup)
                     (done)))
            (.catch (fn [cause]
                      (t/is false (str "exported progressive load failed: " cause))
                      (cleanup)
                      (done))))))))

(t/deftest invalid-args-keep-the-current-owner
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (load-result (scene-args 7 {})
                     (fn [loaded]
                       (t/is (= "ok" (:status loaded)))
                       (let [owner  @@#'browser/canvas*
                             epoch  @@#'browser/owner-epoch*]
                         (load-result (transit/encode-str {:seed -1})
                                      (fn [invalid]
                                        (t/is (= "invalid-args" (:phase invalid)))
                                        (t/is (identical? owner @@#'browser/canvas*))
                                        (t/is (= epoch @@#'browser/owner-epoch*))
                                        (t/is (zero? (effect-count (read-calls) "remove")))
                                        (cleanup)
                                        (done))))))))))

(t/deftest compiled-pan-finishes-at-its-final-render
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (register-phase-case {:context :reuse}
                             #(camera/pan* % {:dx 10 :dy 5 :steps 2 :settle-ms 0}))
        (load-result (scene-args 7 {})
                     (fn [m]
                       (let [phase (first (:phases m))
                             event (first (:events phase))
                             trace (:trace event)]
                         (t/is (= "ok" (:status m)))
                         (t/is (= :main (:phase phase)))
                         (t/is (= ::camera/pan (:command event)))
                         (t/is (= 2 (count (:cached-slices trace))))
                         (t/is (= 0 (:settling-requested-ms trace)))
                         (t/is (= [4] (mapv :flags (:slices trace))))
                         (t/is (= [2] (mapv :frame-type (:slices trace))))
                         (t/is (= 2 (effect-count (read-calls) "render"))
                               "preparation and camera finalization each render once"))
                       (cleanup)
                       (done)))))))

(t/deftest ^:async reused-context-prepares-once-and-resets-each-main
  (await
   (with-entry-env
     (^:async fn [cleanup]
       (try
         (let [views   (atom [])
               options {:dy 0 :steps 1 :settle-ms 0}
               pan     (fn [phase dx rtx]
                         (swap! views conj [phase (get-in rtx [:view :x])])
                         (camera/pan* rtx (assoc options :dx dx)))]
           (register-phase-case {:context :reuse
                                 :preparation {:callback #(pan :preparation 5 %)}
                                 :reset #(pan :reset -10 %)}
                                #(pan :main 10 %))
           (let [prepared (transit/decode-str (await (browser/prepare-scene-request (scene-args 7 {}))))
                 prep     (:preparation prepared)
                 request  (transit/encode-str {:preparation-id (:preparation-id prep)})
                 first    (transit/decode-str (await (browser/run-prepared request)))
                 second   (transit/decode-str (await (browser/run-prepared request)))]
             (t/is (= ["ok" "ok" "ok"] (mapv :status [prepared first second])))
             (t/is (= [[:preparation 0] [:main 5] [:reset 15] [:main 5] [:reset 15]] @views))
             (t/is (= [:preparation] (mapv :phase (:phases prep))))
             (t/is (= [::camera/pan] (mapv :command (get-in prep [:phases 0 :events]))))
             (t/is (= prep (:preparation first) (:preparation second)))
             (doseq [execution [first second]]
               (t/is (= [:main :reset] (mapv :phase (:phases execution))))
               (t/is (= [:completed :completed] (mapv :outcome (:phases execution))))
               (t/is (= [[::camera/pan] [::camera/pan]]
                        (mapv #(mapv :command (:events %)) (:phases execution)))))
             (t/is (= 1 (effect-count (read-calls) "create")))
             (t/is (= 6 (effect-count (read-calls) "render"))
                   "the callback, initial Full, and four main/reset gestures render")
             (t/is (= 1 (count (filter #(= ["call" "_init_shapes_pool"] %) (read-calls)))))))
         (finally
           (cleanup)))))))

(t/deftest ^:async reused-preparation-keeps-its-original-case-deadline
  (await
   (with-entry-env
     (^:async fn [cleanup]
       (try
         (unchecked-set js/globalThis "__benchNow" 0)
         (register-phase-case {:context :reuse :preparation {:render :none}} camera/render-sync*)
         (let [request (-> (scene-args 7 {}) transit/decode-str
                           (assoc :timeout-ms 100) transit/encode-str)
               prepared (transit/decode-str (await (browser/prepare-scene-request request)))
               id (get-in prepared [:preparation :preparation-id])]
           (unchecked-set js/globalThis "__benchNow" 60)
           (let [first (transit/decode-str (await (browser/run-prepared
                                                   (transit/encode-str {:preparation-id id :timeout-ms 10000}))))]
             (t/is (= "ok" (:status first))))
           (unchecked-set js/globalThis "__benchNow" 101)
           (let [expired (transit/decode-str (await (browser/run-prepared
                                                     (transit/encode-str {:preparation-id id}))))]
             (t/is (= "failed" (:status expired)))
             (t/is (= "deadline" (:phase expired)))))
         (finally
           (cleanup)))))))

(t/deftest ^:async fresh-contexts-prepare-and-reset-once
  (await
   (with-entry-env
     (^:async fn [cleanup]
       (try
         (let [visits (atom [])
               work   (fn [phase rtx]
                        (swap! visits conj phase)
                        (camera/render-sync* rtx))]
           (register-phase-case {:preparation {:callback #(work :preparation %)}
                                 :reset #(work :reset %)}
                                #(work :main %))
           (dotimes [_ 2]
             (let [prepared (transit/decode-str (await (browser/prepare-scene-request (scene-args 7 {}))))
                   prep     (:preparation prepared)
                   request  (transit/encode-str {:preparation-id (:preparation-id prep)})
                   evidence (transit/decode-str (await (browser/run-prepared request)))
                   repeated (transit/decode-str (await (browser/run-prepared request)))]
               (t/is (= "ok" (:status evidence)))
               (t/is (= [:main :reset] (mapv :phase (:phases evidence))))
               (t/is (= [::camera/render-sync] (mapv :command (get-in prep [:phases 0 :events]))))
               (t/is (= "invalid-args" (:phase repeated)))))
           (t/is (= [:preparation :main :reset :preparation :main :reset] @visits))
           (t/is (= 2 (effect-count (read-calls) "create")))
           (t/is (= 2 (effect-count (read-calls) "render"))))
         (finally
           (cleanup)))))))

(t/deftest ^:async preparation-policy-keeps-selected-events-and-empty-main
  (doseq [context [:fresh :reuse]
          render [nil :none]]
    (await
     (with-entry-env
       (^:async fn [cleanup]
         (try
           (unchecked-set js/globalThis "__benchNow" 0)
           (register-phase-case
            {:params {:count 2}
             :context context
             :preparation (cond-> {:callback #(-> % (camera/render-sync*) (camera/render-sync*))}
                            render (assoc :render render))}
            identity)
           (let [evidence (transit/decode-str (await (browser/load-scene (scene-args 7 {}))))
                 prep     (:preparation evidence)
                 block    (first (:phases prep))]
             (t/is (= "ok" (:status evidence)))
             (t/is (= :completed (:outcome block)))
             (t/is (= [{:command ::camera/render-sync :metrics {:command/elapsed [0]}}
                       {:command ::camera/render-sync :metrics {:command/elapsed [0]}}]
                      (:events block)))
             (t/is (= [{:phase :main :preparation-id (:preparation-id prep)
                        :outcome :completed :events []}]
                      (:phases evidence)))
             (t/is (= (if (= render :none) 0 1) (effect-count (read-calls) "render"))))
           (finally
             (cleanup))))))))

(t/deftest ^:async reused-context-carries-view-without-a-reset
  (await
   (with-entry-env
     (^:async fn [cleanup]
       (try
         (let [views (atom [])]
           (register-phase-case {:params {:count 2} :context :reuse}
                                (fn [rtx]
                                  (swap! views conj (:view rtx))
                                  (camera/pan rtx {:dx 10 :dy 5 :steps 1 :settle-ms 0})))
           (let [prepared (transit/decode-str (await (browser/prepare-scene-request (scene-args 7 {}))))
                 request  (transit/encode-str {:preparation-id (get-in prepared [:preparation :preparation-id])})
                 first    (transit/decode-str (await (browser/run-prepared request)))
                 second   (transit/decode-str (await (browser/run-prepared request)))]
             (t/is (= ["ok" "ok"] (mapv :status [first second])))
             (t/is (= [{:scale 1 :x 0 :y 0} {:scale 1 :x 10 :y 5}] @views))
             (t/is (= [[] []] (mapv #(get-in % [:phases 0 :events]) [first second])))))
         (finally
           (cleanup)))))))

(t/deftest ^:async phase-failure-keeps-raw-evidence-and-stops-the-context
  (doseq [context [:fresh :reuse]
          failed-phase [:preparation :main :reset]]
    (await
     (with-entry-env
       (^:async fn [cleanup]
         (try
           (unchecked-set js/globalThis "__benchNow" 0)
           (let [visits (atom [])
                 work   (fn [phase rtx]
                          (swap! visits conj phase)
                          (-> (camera/render-sync* rtx)
                              (.then (fn [next-rtx]
                                       (when (= phase failed-phase)
                                         (throw (js/Error. "phase fixture failed")))
                                       next-rtx))))]
             (register-phase-case {:params {:count 2} :context context
                                   :preparation {:callback #(work :preparation %)}
                                   :reset #(work :reset %)}
                                  #(work :main %))
             (let [request     (-> (transit/decode-str (scene-args 7 {}))
                                   (assoc :preparation-id "phase-failure")
                                   (transit/encode-str))
                   prepared    (transit/decode-str (await (browser/prepare-scene-request request)))
                   run         (transit/encode-str {:preparation-id "phase-failure"})
                   evidence    (if (= failed-phase :preparation)
                                 prepared
                                 (transit/decode-str (await (browser/run-prepared run))))
                   stopped     (transit/decode-str (await (browser/run-prepared run)))
                   blocks      (:phases evidence)
                   diagnostic  (errors/project-diagnostic evidence)
                   preparation (if (= failed-phase :preparation)
                                 {:preparation-id "phase-failure" :case-id :rects/lifecycle
                                  :warmup? false :diagnostics (get-in evidence [:partial :setup])
                                  :phases blocks :failure diagnostic}
                                 (assoc (:preparation prepared) :warmup? false))
                   saved       (-> (cond->
                                    (-> (result/create-run
                                         {:run-id "phase-failure" :started-at "start"
                                          :plan {:cases [(load-case)] :warmups 0 :repetitions 1}
                                          :metadata {:git {:sha nil :dirty nil} :build {:functional {}}
                                                     :environment {} :scored? true}})
                                        (result/record-preparation preparation))
                                     (or (= context :fresh) (not= failed-phase :preparation))
                                     (result/record-attempt
                                      (measurement/attempt :rects/lifecycle "phase-failure" false
                                                           (if (= failed-phase :preparation)
                                                             (dissoc evidence :phases)
                                                             evidence))))
                                   (result/record-case-outcome :rects/lifecycle
                                                               {:outcome :failed})
                                   (result/finish-run "end" {:reason :failed})
                                   result/encode result/decode)
                   stored      (if (= failed-phase :preparation)
                                 (first (:preparations saved))
                                 (first (:attempts saved)))]
               (t/is (= "failed" (:status evidence)))
               (t/is (= (name failed-phase) (:phase evidence)))
               (t/is (= "phase fixture failed" (:message evidence)))
               (t/is (= (:cause evidence) (get-in stored [:failure :cause])))
               (t/is (= blocks (:phases stored)))
               (t/is (= "phase-failure" (:preparation-id stored)))
               (t/is (str/includes? (format/format-run (summary/summarize saved)) "phase fixture failed"))
               (t/is (= (case failed-phase
                          :preparation [:preparation]
                          :main [:preparation :main]
                          :reset [:preparation :main :reset])
                        @visits))
               (t/is (= (if (= failed-phase :reset) [:main :reset] [failed-phase])
                        (mapv :phase blocks)))
               (t/is (= :failed (:outcome (last blocks))))
               (t/is (= (repeat (count blocks)
                                [{:command ::camera/render-sync :metrics {:command/elapsed [0]}}])
                        (map :events blocks)))
               (when (= failed-phase :preparation)
                 (let [setup (get-in evidence [:partial :setup])]
                   (t/is (number? (:module-ms setup)))
                   (t/is (number? (:graphics-ms setup)))
                   (t/is (number? (:upload-ms setup)))
                   (t/is (string? (get-in setup [:fingerprint :digest])))))
               (when-not (= failed-phase :preparation)
                 (t/is (= (:preparation prepared) (:preparation evidence)))
                 (t/is (= :completed (get-in evidence [:preparation :phases 0 :outcome]))))
               (t/is (= "invalid-args" (:phase stopped)))
               (t/is (= 1 (effect-count (read-calls) "remove"))))
             (register-phase-case {:params {:count 2}} camera/render-sync*)
             (let [next-case (transit/decode-str (await (browser/load-scene (scene-args 8 {}))))]
               (t/is (= "ok" (:status next-case)))
               (t/is (= [::camera/render-sync]
                        (mapv :command (get-in next-case [:phases 0 :events]))))))
           (finally
             (cleanup))))))))

(t/deftest ^:async superseded-phase-failure-keeps-its-own-preparation
  (doseq [failed-phase [:main :reset]]
    (await
     (with-entry-env
       (^:async fn [cleanup]
         (try
           (let [entered (volatile! nil)
                 reject  (volatile! nil)
                 started (js/Promise. (fn [resolve _reject] (vreset! entered resolve)))
                 work    (fn [phase rtx]
                           (-> (camera/render-sync* rtx)
                               (.then (fn [next-rtx]
                                        (if (= phase failed-phase)
                                          (js/Promise. (fn [_resolve fail]
                                                         (vreset! reject fail)
                                                         (@entered nil)))
                                          next-rtx)))))]
             (register-phase-case {:params {:count 2} :context :reuse
                                   :reset #(work :reset %)}
                                  #(work :main %))
             (let [request (-> (transit/decode-str (scene-args 7 {}))
                               (assoc :preparation-id "first")
                               (transit/encode-str))
                   prepared (transit/decode-str (await (browser/prepare-scene-request request)))
                   first-run (browser/run-prepared (transit/encode-str {:preparation-id "first"}))]
               (await started)
               (register-phase-case {:params {:count 2}} camera/render-sync*)
               (let [next-request (-> (transit/decode-str (scene-args 8 {}))
                                      (assoc :preparation-id "second")
                                      (transit/encode-str))
                     next-prepared (transit/decode-str (await (browser/prepare-scene-request next-request)))]
                 (@reject (js/Error. "late phase failure"))
                 (let [failed (transit/decode-str (await first-run))
                       next-run (transit/decode-str
                                 (await (browser/run-prepared (transit/encode-str {:preparation-id "second"}))))]
                   (t/is (= "failed" (:status failed)))
                   (t/is (= (name failed-phase) (:phase failed)))
                   (t/is (= (:preparation prepared) (:preparation failed)))
                   (t/is (= (if (= failed-phase :main) [:main] [:main :reset])
                            (mapv :phase (:phases failed))))
                   (t/is (= :failed (:outcome (last (:phases failed)))))
                   (t/is (= (repeat (count (:phases failed)) "first")
                            (map :preparation-id (:phases failed))))
                   (t/is (= ["ok" "ok"] (mapv :status [next-prepared next-run])))
                   (t/is (= "second" (get-in next-run [:preparation :preparation-id])))))))
           (finally
             (cleanup))))))))

(t/deftest rejected-camera-body-releases-owner
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (unchecked-set js/globalThis "__benchThrowIn" "_render_from_cache")
        (register-phase-case {:context :reuse}
                             #(camera/pan* % {:dx 10 :dy 5 :steps 2 :settle-ms 0}))
        (load-result (scene-args 7 {})
                     (fn [m]
                       (t/is (= "failed" (:status m)))
                       (t/is (= "main" (:phase m)))
                       (t/is (= 1 (effect-count (read-calls) "remove")))
                       (t/is (nil? @@#'browser/canvas*))
                       (cleanup)
                       (done)))))))

(t/deftest rejected-import-resolves-module-init-failure
  (t/async done
    ;; Needs the entry env for the import polyfill, even though the import
    ;; itself fails before touching the DOM.
    (with-entry-env
      (fn [cleanup]
        (load-result (transit/encode-str
                      {:case (assoc (load-case) :scene-seed 7)
                       :module-url "data:text/javascript,this is not valid javascript((("})
                     (fn [m]
                       (t/is (= "failed" (:status m)))
                       (t/is (= "module-init" (:phase m)))
                       (t/is (= {} (get-in m [:partial :setup])))
                       (cleanup)
                       (done)))))))

(defn- assert-wasm-cause
  "Shared cause assertions for WASM failures through `load-scene`: plain-data
  vector, wire contract, failing fn name, fake message and WASM code."
  [cause fn-name message-pattern]
  (t/is (vector? cause) "cause is plain-data vector")
  (t/is (decl/transit-round-trips? cause) "cause satisfies wire contract")
  (t/is (some #(= fn-name (:fn %)) cause) "cause names the failing WASM fn")
  (t/is (some #(and (string? (:message %))
                    (re-find message-pattern (:message %)))
              cause)
        "fake message preserved")
  (t/is (some #(let [code (:code %)]
                 (and (some? code)
                      (re-find #"wasm-critical" (str code))))
              cause)
        "WASM code preserved"))

(t/deftest set-browser-failure-releases-registered-context
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (unchecked-set js/globalThis "__benchThrowIn" "_set_browser")
        (load-result (scene-args 7 {})
                     (fn [m]
                       (t/is (= "failed" (:status m)))
                       (t/is (= "graphics-init" (:phase m)))
                       (t/is (= {} (get-in m [:partial :setup])))
                       (t/is (= "graphics init failed" (:message m))
                             "top message stays phase-labeled")
                       (assert-wasm-cause (:cause m)
                                          "_set_browser"
                                          #"fake _set_browser failure")
                       (let [calls (read-calls)]
                         (t/is (= 1 (effect-count calls "register")))
                         (t/is (= 1 (effect-count calls "clean")) "renderer state is released")
                         (t/is (some #{["delete" 7]} calls) "the registered handle is deleted")
                         (t/is (= 1 (effect-count calls "lose")) "the browser context is released")
                         (t/is (= 1 (effect-count calls "remove")) "the canvas is removed"))
                       (t/is (nil? @@#'browser/canvas*) "no owner left behind")
                       (cleanup)
                       (done)))))))

(t/deftest upload-failure-keeps-wasm-cause
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (unchecked-set js/globalThis "__benchThrowIn" "_set_view")
        (load-result (scene-args 7 {})
                     (fn [m]
                       (t/is (= "failed" (:status m)))
                       (t/is (= "upload" (:phase m)))
                       (t/is (= "upload failed" (:message m))
                             "top message stays phase-labeled")
                       (assert-wasm-cause (:cause m)
                                          "_set_view"
                                          #"fake _set_view failure")
                       (let [setup (get-in m [:partial :setup])]
                         (t/is (number? (:module-ms setup)))
                         (t/is (number? (:graphics-ms setup)))
                         (t/is (string? (get-in setup [:fingerprint :digest]))))
                       (cleanup)
                       (done)))))))

(t/deftest nested-stale-marker-does-not-resolve-stale
  (let [terminate @#'browser/terminal-failure
        current   @@#'browser/owner-epoch*
        nested    (ex-info "upload failed"
                           {:phase "upload"}
                           (ex-info "superseded"
                                    {::errors/stale true
                                     :phase "upload"}))
        result    (terminate current nested "aborted")
        m         result]
    (t/is (= "failed" (:status m)) "only the top level decides staleness")
    (t/is (= "upload" (:phase m)))
    (t/is (vector? (:cause m)) "cause still rendered")))

(t/deftest overlapping-load-keeps-the-newer-owner
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (unchecked-set js/globalThis "__benchGateRaf" true)
        (let [args       (fn [seed] (scene-args seed {}))
              first-load (browser/load-scene (args 7))]
          ;; Microtasks drain before this macrotask: the first load parked on
          ;; its first frame with a live canvas. Macrotask ordering
          ;; (never wall timing) sequences the assertions.
          (js/setTimeout
           (fn []
             (t/is (some? (unchecked-get js/globalThis "__benchRafCb"))
                   "first load is waiting for a frame")
             (let [second-load (browser/load-scene (args 8))]
               (js/setTimeout
                (fn []
                  ;; The second load completed; release the parked frame so
                  ;; the first load observes its superseded epoch.
                  (if-some [parked (unchecked-get js/globalThis "__benchRafCb")]
                    (parked 0)
                    (t/is false "first load never parked on a frame"))
                  (-> (js/Promise.all #js [first-load second-load])
                      (.then (fn [results]
                               (let [[stale ok] (mapv transit/decode-str (array-seq results))]
                                 (t/is (= "stale" (:status stale)) "superseded load resolves stale")
                                 (t/is (= "ok" (:status ok)) "newer owner completes")
                                 (t/is (= 8 (:seed (:scene ok))) "the completer is the second load")
                                 (let [calls (read-calls)]
                                   (t/is (= 1 (effect-count calls "remove")) "superseded canvas is removed")
                                   (t/is (some #{["remove" 1]} calls))
                                   (t/is (nil? (some #{["remove" 2]} calls)) "newer canvas survives"))
                                 (t/is (some? @@#'browser/canvas*) "the newer owner stays live")
                                 (t/is (true? wasm/context-initialized?)))
                               (cleanup)
                               (done)))
                      (.catch (fn [cause]
                                (t/is false (str "must resolve, threw: " cause))
                                (cleanup)
                                (done)))))
                0)))
           0))))))
