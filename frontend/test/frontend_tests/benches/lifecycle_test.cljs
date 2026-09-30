;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.lifecycle-test
  "Tests the browser bridge with fake DOM and renderer boundaries.

  It covers bridge input, ownership, disposal and `load-scene` with fake
  canvases and a fake Emscripten factory. Protocol timing tests use an
  injected clock in `frontend-tests.benches.protocol-test`."
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [app.common.transit :as transit]
   [benches.render-wasm.browser :as browser]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.scenes.core :as core]
   [cljs.test :as t :include-macros true]))

;; Forward declaration: entry-test helpers below build args against the
;; fake Emscripten factory URL defined alongside the fake-DOM harness.
(declare factory-url)

;; Partial-init unwinding of `init-context` is covered in
;; `frontend-tests.render-wasm.webgl-test`, which also pins the success,
;; nil-context and registration-failure cases.

(defn- load-case
  "Collected `:rects/load` descriptor for entry tests."
  []
  (first (cases/collect-cases {:master-seed 42 :filter "rects/load"})))

(defn- scene-args
  "Transit request for `browser/load-scene`. `overrides` change the
  collected case descriptor."
  [seed overrides]
  (transit/encode-str
   {:seed seed
    :case (merge (load-case) overrides)
    :module-url (factory-url)
    :wasm-url "./fake.wasm"}))

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
    (expect-failed-args (transit/encode-str {:case (load-case)}) true done)))

(t/deftest missing-case-fails-before-timers
  (t/async done
    (expect-failed-args (transit/encode-str {:seed 42}) true done)))

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
           (core/resolve-viewport {:scale 1 :x 0 :y 0})))
  (t/is (= {:width 800 :height 600 :dpr 2}
           (core/resolve-viewport {:scale 1 :x 0 :y 0
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
        (t/is (true? (:benches.render-wasm.failures/stale (ex-data thrown)))
              "the marker lives in ex-data, never in message text")))
    (t/testing "terminal stale resolves without disposal"
      (let [before @@#'browser/owner-epoch*
            result (terminate epoch
                              (ex-info "superseded"
                                       {:benches.render-wasm.failures/stale true
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

(t/deftest bridge-exposes-pilot-entries
  (let [keys (js/Object.keys browser/bridge)]
    (t/is (some #{"ping"} keys))
    (t/is (some #{"loadScene"} keys))
    (t/is (some #{"dispose"} keys))))

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
  [id]
  (let [lose-ext #js {:loseContext (fn [] (record! ["lose" id]) nil)}
        context  #js {:getExtension (fn [name]
                                      (when (= name "WEBGL_lose_context")
                                        lose-ext))
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
         :remove (fn [] (record! ["remove" id]) nil)}))

(defn- install-fake-dom!
  "Installs fake `document`/`window`/`dynamicImport` globals and returns a
  `:restore!` thunk that puts the previous values back. Created canvases are
  numbered from 1 per installation. The import polyfill mirrors
  `resources/polyfills/dynamicImport.js`, which ships with the app HTML but
  not with the unit-test bundle."
  []
  (let [prev-document (unchecked-get js/globalThis "document")
        prev-window   (unchecked-get js/globalThis "window")
        prev-import   (unchecked-get js/globalThis "dynamicImport")
        next-id       (atom 0)
        document      #js {:createElement (fn [_]
                                            (let [id (swap! next-id inc)]
                                              (record! ["create" id])
                                              (fake-canvas id)))
                           :body #js {:appendChild (fn [canvas]
                                                     (record! ["append" (unchecked-get canvas "id")])
                                                     canvas)}
                           :querySelectorAll (fn [_] #js [])}
        window        #js {:requestAnimationFrame
                           (fn [cb]
                             ;; Park the first frame while `__benchGateRaf`
                             ;; is set so a second load can supersede a live,
                             ;; active owner; later frames run synchronously.
                             (if (and (unchecked-get js/globalThis "__benchGateRaf")
                                      (nil? (unchecked-get js/globalThis "__benchRafCb")))
                               (unchecked-set js/globalThis "__benchRafCb" cb)
                               (cb 0))
                             nil)}
        dynamic-import (fn [url] (js/eval (str "import(" (pr-str url) ")")))]
    (unchecked-set js/globalThis "document" document)
    (unchecked-set js/globalThis "window" window)
    (unchecked-set js/globalThis "dynamicImport" dynamic-import)
    (fn restore! []
      (unchecked-set js/globalThis "document" prev-document)
      (unchecked-set js/globalThis "window" prev-window)
      (unchecked-set js/globalThis "dynamicImport" prev-import))))

(def ^:private factory-source
  "ES module stubbing the Emscripten factory for entry tests. Records every
  renderer call in `globalThis.__benchCalls`; `globalThis.__benchThrowIn`
  names one module fn that throws. The instance trap answers `undefined` for
  `then` and non-strings: promise assimilation would otherwise treat the
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
       "_render: function () { push(['render']); return 2; },"
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
  (unchecked-set js/globalThis "__benchRafCb" nil))

(defn- read-calls
  "Call log as Clojure data."
  []
  (js->clj (unchecked-get js/globalThis "__benchCalls")))

(defn- effect-count
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
  `cleanup` thunk that disposes the owner, clears the recording and restores
  the globals. Call `cleanup` after asserting, before `done`."
  [f]
  (let [restore-dom! (install-fake-dom!)]
    (reset-bench-globals!)
    (f (fn []
         (browser/dispose!)
         (reset-bench-globals!)
         (restore-dom!)))))

(t/deftest successful-load-reaches-full
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (load-result (scene-args 7 {})
                     (fn [m]
                       (t/is (= "ok" (:status m)))
                       (t/is (= 1 (:render-frames m)))
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

(t/deftest warm-pan-restores-and-reaches-full
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (load-result (scene-args 7 {:id :rects/pan :context :reuse})
                     (fn [m]
                       (t/is (= "ok" (:status m)))
                       (t/is (= 20 (count (:cached-slices m))))
                       (t/is (= 100 (:settling-requested-ms m)))
                       (t/is (= [4] (mapv :flags (:slices m))))
                       (t/is (= [2] (mapv :frame-type (:slices m))))
                       (t/is (= 2 (effect-count (read-calls) "render"))
                             "restore and finalization each drain once")
                       (cleanup)
                       (done)))))))

(t/deftest warm-zoom-reaches-full
  (t/async done
    (with-entry-env
      (fn [cleanup]
        (load-result (scene-args 7 {:id :rects/zoom :context :reuse})
                     (fn [m]
                       (t/is (= "ok" (:status m)))
                       (t/is (= 20 (count (:cached-slices m))))
                       (t/is (= [2] (mapv :frame-type (:slices m))))
                       (cleanup)
                       (done)))))))

(t/deftest rejected-import-resolves-module-init-failure
  (t/async done
    ;; Needs the entry env for the import polyfill, even though the import
    ;; itself fails before touching the DOM.
    (with-entry-env
      (fn [cleanup]
        (load-result (transit/encode-str
                      {:seed 7
                       :case (load-case)
                       :module-url "data:text/javascript,this is not valid javascript((("})
                     (fn [m]
                       (t/is (= "failed" (:status m)))
                       (t/is (= "module-init" (:phase m)))
                       (cleanup)
                       (done)))))))

(defn- assert-wasm-cause
  "Shared cause assertions for WASM failures through `load-scene`: plain-data
  vector, wire contract, failing fn name, fake message and WASM code."
  [cause fn-name message-pattern]
  (t/is (vector? cause) "cause is plain-data vector")
  (t/is (core/transit-round-trips? cause) "cause satisfies wire contract")
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
                       (cleanup)
                       (done)))))))

(t/deftest hostile-cause-renders-plain-data
  (let [terminate @#'browser/terminal-failure
        current   @@#'browser/owner-epoch*
        hostile   (ex-info "boom"
                           {:fn   (fn [] 1)
                            :code #js {:evil 1}
                            :type :wasm-error
                            :hint (apply str (repeat 1000 "x"))}
                           (js/Error. "inner boom"))
        result    (terminate current hostile "aborted")
        m         result
        cause     (:cause m)]
    (t/is (= "failed" (:status m)))
    (t/is (= "aborted" (:phase m)))
    (t/is (= "boom" (:message m)) "top message stays as-is")
    (t/is (vector? cause) "cause is plain-data vector")
    (t/is (core/transit-round-trips? cause) "hostile values become strings")
    (t/is (every? (fn [entry]
                    (every? (fn [[_ v]]
                              (or (not (string? v)) (<= (count v) 500)))
                            entry))
                  cause)
          "strings truncated to the hard cap")
    (let [outer (first cause)]
      (t/is (string? (:fn outer)) "function value stringified")
      (t/is (string? (:code outer)) "host object value stringified")
      (t/is (= 500 (count (:hint outer))) "long hint truncated"))
    (t/is (some #(and (string? (:message %))
                      (re-find #"inner boom" (:message %)))
                cause)
          "inner message preserved"))
  (t/testing "values pr-str cannot render fall back to plain strings"
    (let [evil      (js-obj)
          _         (js/Object.defineProperty
                     evil "evil"
                     #js {:enumerable true
                          :get (fn [] (throw (js/Error. "evil")))})
          terminate @#'browser/terminal-failure
          current   @@#'browser/owner-epoch*
          hostile   (ex-info "evil boom" {:fn evil} (js/Error. "inner"))
          result    (terminate current hostile "aborted")
          m         result
          cause     (:cause m)
          outer     (first cause)]
      (t/is (= "failed" (:status m)))
      (t/is (core/transit-round-trips? cause) "throwing value still crosses the wire")
      (t/is (= "unrenderable value" (:fn outer))
            "stringification failure falls back")))
  (t/testing "causes pr-str cannot render fall back to plain strings"
    (let [evil      (js-obj)
          _         (js/Object.defineProperty
                     evil "evil"
                     #js {:enumerable true
                          :get (fn [] (throw (js/Error. "evil")))})
          terminate @#'browser/terminal-failure
          current   @@#'browser/owner-epoch*
          result    (terminate current evil "aborted")
          m         result
          cause     (:cause m)]
      (t/is (= "failed" (:status m)))
      (t/is (core/transit-round-trips? cause) "throwing cause still crosses the wire")
      (t/is (= "unrenderable cause" (:message (first cause)))
            "level fallback holds"))))

(t/deftest non-finite-failure-details-cross-transit-as-numbers
  (let [terminate @#'browser/terminal-failure
        current   @@#'browser/owner-epoch*
        failure   (ex-info "bad measurement"
                           {:code js/NaN
                            :hint js/Infinity
                            :type js/-Infinity})
        result    (terminate current failure "aborted")
        decoded   (-> result transit/encode-str transit/decode-str)
        detail    (first (:cause decoded))]
    (t/is (number? (:code detail)))
    (t/is (js/Number.isNaN (:code detail)))
    (t/is (= js/Infinity (:hint detail)))
    (t/is (= js/-Infinity (:type detail)))))

(t/deftest nested-stale-marker-does-not-resolve-stale
  (let [terminate @#'browser/terminal-failure
        current   @@#'browser/owner-epoch*
        nested    (ex-info "upload failed"
                           {:phase "upload"}
                           (ex-info "superseded"
                                    {:benches.render-wasm.failures/stale true
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
