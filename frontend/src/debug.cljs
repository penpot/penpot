;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns debug
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.logic.libraries :as cll]
   [app.common.path-names :as cpn]
   [app.common.pprint :as pp]
   [app.common.render-wasm.helpers :as wasm.h]
   [app.common.render-wasm.mem :as wasm.mem]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.transit :as t]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dwc]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as dps]
   [app.main.data.preview :as dp]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwcm]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shortcuts]
   [app.main.data.workspace.undo :as dwu]
   [app.main.errors :as errors]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(l/set-level! :debug)

(defn- coerce-keyword
  "Coerce `v` to a keyword when it is a string or keyword, nil otherwise."
  [v]
  (when (or (keyword? v) (string? v))
    (keyword v)))

(defn ^:export set-logging
  ([level]
   (let [level (coerce-keyword level)]
     (if (l/valid-level? level)
       (l/set-level! "app" level)
       (js/console.warn "ignoring invalid log level:" (pr-str level)))))
  ([ns level]
   (let [ns    (coerce-keyword ns)
         level (coerce-keyword level)]
     (if (and (l/valid-logger? (some-> ns name))
              (l/valid-level? level))
       (l/set-level! (name ns) level)
       (js/console.warn "ignoring invalid logging config:" (pr-str ns) (pr-str level))))))

;; These events are excluded when we activate the :events flag
(def debug-exclude-events
  #{:app.main.data.workspace.notifications/handle-pointer-update
    :app.main.data.workspace.notifications/handle-pointer-send
    :app.main.data.websocket/send-message
    :app.main.data.workspace.selection/change-hover-state})

(defn enable!
  [option]
  (dbg/enable! option)
  (case option
    :events
    (set! st/*debug-events* true)

    :events-times
    (set! st/*debug-events-time* true)

    nil)
  (js* "app.main.reinit()"))

(defn disable!
  [option]
  (dbg/disable! option)
  (case option
    :events
    (set! st/*debug-events* false)

    :events-times
    (set! st/*debug-events-time* false)

    nil)
  (js* "app.main.reinit()"))

(defn ^:export toggle-debug
  [name]
  (let [option (keyword name)]
    (if (dbg/enabled? option)
      (disable! option)
      (enable! option))))

(defn ^:export debug-all
  []
  (reset! dbg/state dbg/options)
  (js* "app.main.reinit()"))

(defn ^:export debug-none
  []
  (reset! dbg/state #{})
  (js* "app.main.reinit()"))

(defn ^:export tap
  "Transducer function that can execute a side-effect `effect-fn` per input"
  [effect-fn]
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (effect-fn input)
       (rf result input)))))

(defn ^:export logjs
  ([str] (tap (partial logjs str)))
  ([str val]
   (js/console.log str (json/->js val))
   val))

(defn- wasm-read-len-prefixed-utf8
  "Reads a `[u32 byte_len][utf8 bytes...]` buffer returned by WASM and frees it.
   Returns a JS string (possibly empty)."
  [ptr]
  (when (and ptr (not (zero? ptr)))
    (let [heap-u8  (wasm.mem/get-heap-u8)
          heap-u32 (wasm.mem/get-heap-u32)
          len      (aget heap-u32 (wasm.mem/->offset-32 ptr))
          start    (+ ptr 4)
          end      (+ start len)
          decoder  (js/TextDecoder. "utf-8")
          text     (.decode decoder (.subarray heap-u8 start end))]
      (wasm.mem/free)
      text)))

(defn ^:export wasmCaptureFrames
  [amount]
  (let [module wasm/internal-module
        f      (when module (unchecked-get module "_capture_frames"))]
    (if (fn? f)
      (wasm.h/call module "_capture_frames" amount)
      (js/console.warn "[debug] render-wasm module not ready or missing _render_stats"))))

(defn ^:export wasmRenderStats
  []
  (let [module wasm/internal-module
        f      (when module (unchecked-get module "_render_stats"))]
    (if (fn? f)
      (wasm.h/call module "_render_stats")
      (js/console.warn "[debug] render-wasm module not ready or missing _render_stats"))))

(defn ^:export wasmAtlasConsole
  "Logs the current render-wasm atlas as an image in the JS console (if present)."
  []
  (let [module wasm/internal-module
        f      (when module (unchecked-get module "_debug_atlas_console"))]
    (if (fn? f)
      (wasm.h/call module "_debug_atlas_console")
      (js/console.warn "[debug] render-wasm module not ready or missing _debug_atlas_console"))))

(defn ^:export wasmAtlasBase64
  "Returns the atlas PNG base64 (empty string if missing/empty)."
  []
  (let [module wasm/internal-module
        f      (when module (unchecked-get module "_debug_atlas_base64"))]
    (if (fn? f)
      (let [ptr (wasm.h/call module "_debug_atlas_base64")
            s   (or (wasm-read-len-prefixed-utf8 ptr) "")]
        s)
      (do
        (js/console.warn "[debug] render-wasm module not ready or missing _debug_atlas_base64")
        ""))))

(defn ^:export wasmSurfaceConsole
  "Logs the render-wasm surface id as an image in the JS console."
  [id]
  (let [module wasm/internal-module
        f      (when module (unchecked-get module "_debug_surface_console"))]
    (if (fn? f)
      (wasm.h/call module "_debug_surface_console" id)
      (js/console.warn "[debug] render-wasm module not ready or missing _debug_surface_console"))))

(defn ^:export wasmCacheConsole
  "Logs the current render-wasm cache surface as an image in the JS console."
  []
  (let [module wasm/internal-module
        f      (when module (unchecked-get module "_debug_cache_console"))]
    (if (fn? f)
      (wasm.h/call module "_debug_cache_console")
      (js/console.warn "[debug] render-wasm module not ready or missing _debug_cache_console"))))

(defn ^:export wasmCacheBase64
  "Returns the cache surface PNG base64 (empty string if missing/empty)."
  []
  (let [module wasm/internal-module
        f      (when module (unchecked-get module "_debug_cache_base64"))]
    (if (fn? f)
      (let [ptr (wasm.h/call module "_debug_cache_base64")
            s   (or (wasm-read-len-prefixed-utf8 ptr) "")]
        s)
      (do
        (js/console.warn "[debug] render-wasm module not ready or missing _debug_cache_base64")
        ""))))

(when (exists? js/window)
  (set! (.-dbg ^js js/window) json/->js)
  (set! (.-pp ^js js/window) pprint))

(defonce widget-style "
  background: black;
  bottom: 10px;
  color: white;
  height: 20px;
  padding-left: 8px;
  position: absolute;
  right: 10px;
  width: 40px;
  z-index: 99999;
  opacity: 0.5;
")

(defn ^:export dump-state []
  (logjs "state" @st/state)
  nil)

(defn ^:export dump-data []
  (let [fdata (-> (dsh/lookup-file @st/state)
                  (get :data))]
    (logjs "file-data" fdata)
    nil))

(defn ^:export dump-buffer []
  (logjs "last-events" @st/last-events)
  nil)

(defn ^:export get-state [str-path]
  (let [path (->> (str/split str-path " ")
                  (map d/read-string)
                  vec)]
    (js/console.log (clj->js (get-in @st/state path))))
  nil)

(defn dump-objects'
  [state]
  (let [objects (dsh/lookup-page-objects state)]
    (logjs "objects" objects)
    nil))

(defn ^:export dump-objects
  []
  (dump-objects' @st/state))

(defn get-object
  [state name]
  (let [objects (dsh/lookup-page-objects state)
        result  (or (d/seek (fn [shape] (= name (:name shape))) (vals objects))
                    (get objects (uuid/parse name)))]
    result))

(defn ^:export dump-object
  [name]
  (clj->js (get-object @st/state name)))

(defn get-selected
  [state]
  (dsh/lookup-selected state))

(defn ^:export dump-selected
  []
  (let [objects (dsh/lookup-page-objects @st/state)
        result  (->> (get-selected @st/state) (map #(get objects %)))]
    (logjs "selected" result)
    nil))


(defn ^:export dump-selected-edn
  []
  (let [objects (dsh/lookup-page-objects @st/state)
        result  (->> (get-selected @st/state) (map #(get objects %)))]
    (pp/pprint result {:length 30 :level 30})
    nil))

(defn ^:export preview-selected
  []
  (st/emit! (dp/open-preview-selected)))

(defn ^:export parent
  []
  (let [objects     (dsh/lookup-page-objects @st/state)
        selected-id (first (dsh/get-selected-ids @st/state))
        parent-id   (dm/get-in objects [selected-id :parent-id])]
    (when-let [parent (get objects parent-id)]
      (js/console.log (str (:name parent) " - " (:id parent))))
    nil))

(defn ^:export frame
  []
  (let [objects     (dsh/lookup-page-objects @st/state)
        selected-id (first (dsh/get-selected-ids @st/state))
        frame-id    (dm/get-in objects [selected-id :frame-id])]
    (when-let [frame (get objects frame-id)]
      (js/console.log (str (:name frame) " - " (:id frame))))
    nil))

(defn ^:export select-by-object-id
  [object-id]
  (let [[_ page-id shape-id _] (str/split object-id #"/")]
    (st/emit! (dcm/go-to-workspace :page-id (uuid/parse page-id)))
    (st/emit! (dws/select-shape (uuid/parse shape-id)))))

(defn ^:export select-by-id
  [shape-id]
  (st/emit! (dws/select-shape (uuid/parse shape-id))))

(defn dump-tree'
  ([state] (dump-tree' state false false false))
  ([state show-ids] (dump-tree' state show-ids false false))
  ([state show-ids show-touched] (dump-tree' state show-ids show-touched false))
  ([state show-ids show-touched show-modified]
   (let [page-id    (get state :current-page-id)
         file       (dsh/lookup-file state)
         libraries  (get state :files)]
     (ctf/dump-tree file page-id libraries {:show-ids show-ids
                                            :show-touched show-touched
                                            :show-modified show-modified}))))
(defn ^:export dump-tree
  ([] (dump-tree' @st/state))
  ([show-ids] (dump-tree' @st/state show-ids false false))
  ([show-ids show-touched] (dump-tree' @st/state show-ids show-touched false))
  ([show-ids show-touched show-modified] (dump-tree' @st/state show-ids show-touched show-modified)))

(defn ^:export dump-subtree'
  ([state shape-id] (dump-subtree' state shape-id false false false))
  ([state shape-id show-ids] (dump-subtree' state shape-id show-ids false false))
  ([state shape-id show-ids show-touched] (dump-subtree' state shape-id show-ids show-touched false))
  ([state shape-id show-ids show-touched show-modified]
   (let [page-id    (get state :current-page-id)
         file       (dsh/lookup-file state)
         libraries  (get state :files)
         shape-id   (if (some? shape-id)
                      (uuid/parse shape-id)
                      (first (dsh/lookup-selected state)))]
     (if (some? shape-id)
       (ctf/dump-subtree file page-id shape-id libraries {:show-ids show-ids
                                                          :show-touched show-touched
                                                          :show-modified show-modified})
       (println "no selected shape")))))

(defn ^:export dump-subtree
  ([shape-id] (dump-subtree' @st/state shape-id))
  ([shape-id show-ids] (dump-subtree' @st/state shape-id show-ids false false))
  ([shape-id show-ids show-touched] (dump-subtree' @st/state shape-id show-ids show-touched false))
  ([shape-id show-ids show-touched show-modified] (dump-subtree' @st/state shape-id show-ids show-touched show-modified)))

(defn ^:export apply-changes
  "Takes a Transit JSON changes"
  [^string changes*]

  (let [file-id (:current-file-id @st/state)
        changes (t/decode-str changes*)]
    (st/emit! (dwc/commit-changes {:redo-changes changes
                                   :undo-changes []
                                   :save-undo? true
                                   :file-id file-id}))))

(defn ^:export fetch-apply
  [^string url]
  (-> (p/let [response (js/fetch url)]
        (.text response))
      (p/then apply-changes)))

(def ^:private simulated-error-body
  "Stands in for the error page a proxy or CDN writes when it answers instead
  of the backend."
  "<html><head><title>Simulated error</title></head><body>simulated intermediary response</body></html>")

(defonce ^:private pending-http-error (atom nil))

(defn- simulated-status
  "Status to answer `uri` with, or nil to let the request through. A
  simulation without a deadline answers one call; one with a deadline answers
  every matching call until it runs out."
  [uri]
  (let [{:keys [method status until]} @pending-http-error]
    (when (and (some? method)
               (str/includes? uri (str "/api/main/methods/" method)))
      (cond
        (nil? until)              (do (reset! pending-http-error nil) status)
        (< (js/Date.now) until)   status
        :else                     (do (reset! pending-http-error nil) nil)))))

;; The original fetch and the wrapper standing in for it, or nil when unhooked.
(defonce ^:private http-error-hook (atom nil))

(defn- install-http-error-hook!
  []
  (when (nil? @http-error-hook)
    (let [original (unchecked-get js/globalThis "fetch")
          wrapper  (fn [input params]
                     (let [uri (if (string? input) input (unchecked-get input "url"))]
                       (if-let [status (simulated-status uri)]
                         (p/resolved (js/Response. simulated-error-body
                                                   #js {:status status
                                                        :headers #js {"content-type" "text/html"}}))
                         (.call original js/globalThis input params))))]
      (reset! http-error-hook {:original original :wrapper wrapper})
      (unchecked-set js/globalThis "fetch" wrapper))))

(defn- remove-http-error-hook!
  []
  (when-let [{:keys [original wrapper]} @http-error-hook]
    ;; Restore only while the wrapper is still installed, so a later
    ;; wrapper survives.
    (when (identical? wrapper (unchecked-get js/globalThis "fetch"))
      (unchecked-set js/globalThis "fetch" original))
    (reset! http-error-hook nil)))

(defn ^:export simulateHttpError
  "Answers calls to an API method with `status` and a body that carries no
  Penpot error code, the way a CDN or a corporate proxy does. Every other
  request goes through untouched.

  Without `seconds` the simulation is spent once it answers one call. With
  `seconds` it answers every matching call for that long, which is how to
  watch a save retry, warn, keep retrying and finally recover.

  debug.simulateHttpError('update-file')
  debug.simulateHttpError('get-comment-threads', 403)
  debug.simulateHttpError('update-file', 524, 120)"
  ([method]
   (simulateHttpError method 524 nil))
  ([method status]
   (simulateHttpError method status nil))
  ([method status seconds]
   (install-http-error-hook!)
   (let [until (when (and (number? seconds) (pos? seconds))
                 (+ (js/Date.now) (* 1000 seconds)))]
     (reset! pending-http-error {:method method
                                 :status status
                                 :until until})
     (if (some? until)
       (str "every " method " call will be answered with " status
            " for the next " seconds " seconds")
       (str "the next " method " call will be answered with " status)))))

(defn ^:export clearHttpErrorSimulation
  "Stops a running simulation and takes the wrapper off `fetch`."
  []
  (reset! pending-http-error nil)
  (remove-http-error-hook!)
  "requests reach the backend again")

(defn ^:export reset-viewport
  []
  (st/emit!
   dw/reset-zoom
   (dw/update-viewport-position {:x (constantly 0) :y (constantly 0)})))

(defn ^:export hide-ui
  []
  (st/emit!
   (dw/toggle-layout-flag :hide-ui)))


(defn ^:export shortcuts
  []

  (letfn [(print-shortcuts [shortcuts]
            (.table js/console
                    (->> shortcuts
                         (map (fn [[key {:keys [command]}]]
                                [(d/name key)
                                 (if (vector? command)
                                   (str/join " | " command)
                                   command)]))
                         (into {})
                         (clj->js))))]
    (let [style "font-weight: bold; font-size: 1.25rem;"]
      (.log js/console "%c Dashboard" style)
      (print-shortcuts app.main.data.dashboard.shortcuts/shortcuts)

      (.log js/console "%c Workspace" style)
      (print-shortcuts app.main.data.workspace.shortcuts/shortcuts)

      (.log js/console "%c Path" style)
      (print-shortcuts app.main.data.workspace.path.shortcuts/shortcuts)

      (.log js/console "%c Viewer" style)
      (print-shortcuts app.main.data.viewer.shortcuts/shortcuts)))
  nil)

(defn ^:export nodeStats
  []
  (let [root-node (dom/query ".viewport .render-shapes")
        num-nodes (->> (dom/seq-nodes root-node) count)]
    #js {:number num-nodes}))

(defn modif->js
  [modif-tree objects]
  (clj->js (into {}
                 (map (fn [[k v]]
                        [(get-in objects [k :name]) v]))
                 modif-tree)))

(defn ^:export dump-modifiers
  []
  (let [objects   (dsh/lookup-page-objects @st/state)
        modifiers (:workspace-modifiers @st/state)]
    (js/console.log (modif->js modifiers objects))
    nil))

(defn ^:export set-workspace-read-only
  [read-only?]
  (st/emit! (dwcm/set-workspace-read-only read-only?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REPAIR & VALIDATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Validation and repair

(defn ^:export validate
  ([] (validate nil))
  ([shape-id]
   (let [file       (dsh/lookup-file @st/state)
         libraries  (get @st/state :files)]
     (try
       (->> (if-let [shape-id (some-> shape-id uuid/parse)]
              (let [page (dm/get-in file [:data :pages-index (get @st/state :current-page-id)])]
                (cfv/validate-shape shape-id file page libraries))
              (cfv/validate-file file libraries))
            (group-by :code)
            (clj->js))
       (catch :default cause
         (ex/print-throwable cause))))))

(defn ^:export validate-schema
  []
  (try
    (let [file (dsh/lookup-file @st/state)]
      (cfv/validate-file-schema! file))
    (catch :default cause
      (ex/print-throwable cause))))

(defn ^:export repair
  [reload?]
  (st/emit!
   (ptk/reify ::repair-current-file
     ptk/EffectEvent
     (effect [_ state _]
       (let [features (:features state)
             sid      (:session-id state)

             file     (dsh/lookup-file state)
             libs     (get state :files)

             errors   (cfv/validate-file file libs)
             _        (l/dbg :hint "repair current file" :errors (count errors))

             changes  (cfr/repair-file file libs errors)

             params    {:id (:id file)
                        :revn (:revn file)
                        :vern (:vern file)
                        :session-id sid
                        :changes changes
                        :features features
                        :skip-validate true}]

         (->> (rp/cmd! :update-file params)
              (rx/subs! (fn [_]
                          (when reload?
                            (dom/reload-current-window)))
                        (fn [cause]
                          (ex/print-throwable cause)))))))))

(defn ^:export fix-orphan-shapes
  []
  (st/emit! (dw/fix-orphan-shapes)))

(defn ^:export find-components-norefs
  []
  (st/emit! (dw/find-components-norefs)))

(defn- set-shape-ref*
  [id shape-ref]
  (ptk/reify ::set-shape-ref
    ptk/WatchEvent
    (watch [_ _ _]
      (let [shape-id (uuid/parse id)
            shape-ref (uuid/parse shape-ref)]
        (rx/of (dw/update-shape shape-id {:shape-ref shape-ref}))))))

(defn ^:export set-shape-ref
  [id shape-ref]
  (st/emit! (set-shape-ref* id shape-ref)))

;; --- Save faults

(def ^:private rejecting-faults
  "Debug faults the backend rejects a commit for."
  #{"validation" "runtime" "ex-info" "assertion"})

(def ^:private request-faults
  "Debug faults that fail the whole save, as an infrastructure error does."
  #{"sql" "io" "interrupted" "not-found" "stack-overflow"})

(defn- debug-plugin-data-change
  "A change that removes plugin data `key` of the file, which is never set,
  so it changes no data."
  [key]
  {:type :set-plugin-data
   :object-type :file
   :namespace :penpot-debug
   :key key
   :value nil})

(defn- save-fault-marker
  "A change that makes the backend fail with `fault` when it applies it
  (`debug-save-faults` backend flag). It changes no data."
  [fault]
  (debug-plugin-data-change (str "save-fault:" fault)))

(defn- fault-error
  "Why `fault` is not one of `faults`, or nil if it is."
  [fault faults]
  (when-not (contains? faults fault)
    (str "unknown fault " fault ", expected one of "
         (str/join ", " (sort faults)))))

;; Armed `:hold` and `:faults` for update-file calls, and the `:original`
;; method while they wrap it.
(defonce ^:private save-hook (atom {}))

(defn- mark-save
  "Adds the fault armed for the file of `params` to the commit it names,
  and counts the save against it."
  [params]
  (let [file-id (:id params)
        [old _] (swap-vals! save-hook
                            (fn [{:keys [faults] :as hook}]
                              (let [{:keys [times]} (get faults file-id)]
                                (cond
                                  (nil? times) hook
                                  (> times 1)  (update-in hook [:faults file-id :times] dec)
                                  :else        (update hook :faults dissoc file-id)))))]
    (if-let [{:keys [fault commit]} (get-in old [:faults file-id])]
      (let [marker (save-fault-marker fault)
            groups (:changes-with-metadata params)]
        (if (seq groups)
          (update-in params [:changes-with-metadata (min commit (dec (count groups))) :changes]
                     conj marker)
          (update params :changes (fnil conj []) marker)))
      params)))

(defn- hooked-update-file
  "Sends an update-file call through `send` with the armed fault, and holds
  its answer while saves are held."
  [send id params]
  (let [response (send id (mark-save params))]
    (if-let [gate (dm/get-in @save-hook [:hold :gate])]
      (rx/mapcat (fn [result]
                   (rx/map (constantly result) (rx/from gate)))
                 response)
      response)))

(defn- sync-save-hook
  "Wraps update-file calls while a hold or a fault is armed, and unwraps
  them when none is."
  []
  (let [{:keys [hold faults original]} @save-hook
        armed? (or (some? hold) (seq faults))]
    (cond
      (and armed? (nil? original))
      (let [original (get-method rp/cmd! :update-file)]
        (swap! save-hook assoc :original original)
        (-add-method rp/cmd! :update-file
                     (fn [id params] (hooked-update-file original id params))))

      (and (not armed?) (some? original))
      (do
        (if (identical? original (get-method rp/cmd! :default))
          (remove-method rp/cmd! :update-file)
          (-add-method rp/cmd! :update-file original))
        (swap! save-hook dissoc :original)))))

(defn ^:export releaseSaves
  "Answers the held saves now and stops holding new ones."
  []
  (when-let [{:keys [gate timer]} (:hold @save-hook)]
    (js/clearTimeout timer)
    (p/resolve gate nil))
  (swap! save-hook dissoc :hold)
  (sync-save-hook)
  "saves are answered as they arrive")

(defn ^:export holdSaves
  "Holds the answers to the saves sent in the next `seconds` (default 5)
  until then, or until `debug.releaseSaves()`. The backend applies each
  save at once; only the tab waits, so edits made meanwhile stay pending
  behind it, as with a slow backend.

  debug.holdSaves()
  debug.holdSaves(10)"
  ([] (holdSaves 5))
  ([seconds]
   (releaseSaves)
   (swap! save-hook assoc :hold {:gate  (p/deferred)
                                 :timer (js/setTimeout releaseSaves (* 1000 seconds))})
   (sync-save-hook)
   (str "holding the answers to saves for " seconds " seconds")))

(defn ^:export failNextSave
  "Makes the backend fail the next `times` saves (default 1) of a file (by
  default the open one) with `fault`, from the commit at index `commit`
  (default 0) on. Needs the `debug-save-faults` backend flag.

  Faults 'validation', 'runtime', 'ex-info' and 'assertion' reject the
  commits from that one on; 'sql', 'io', 'interrupted', 'not-found' and
  'stack-overflow' fail the whole save, as an infrastructure error does.
  A retry is a new save and counts against `times`.

  debug.failNextSave({fault: 'sql'})
  debug.failNextSave({fault: 'runtime', commit: 2, times: 3, fileId: '…'})"
  [options]
  (let [{:keys [fault times commit fileId]
         :or {times 1 commit 0}} (js->clj options :keywordize-keys true)
        file-id (if fileId (uuid/parse fileId) (:current-file-id @st/state))]
    (or (fault-error fault (into rejecting-faults request-faults))
        (do
          (swap! save-hook assoc-in [:faults file-id]
                 {:fault fault :times times :commit commit})
          (sync-save-hook)
          (str "the next " times " saves of " file-id " fail with " fault
               " from commit " commit " on")))))

(defn ^:export clearSaveFaults
  "Disarms every fault armed with `debug.failNextSave`."
  []
  (swap! save-hook dissoc :faults)
  (sync-save-hook)
  "no save fault armed")

(defn- pending-of-file
  "Applies `f` to the pending commits of `file-id`, queued and buffered."
  [pstate file-id f]
  (let [file?    #(= file-id (:file-id %))
        queued   (filter #(file? (get-in pstate [:index %])) (:queue pstate))
        buffered #(if (file? %) (f %) %)]
    (as-> pstate $
      (reduce (fn [pstate id] (update-in pstate [:index id :commits] #(mapv f %))) $ queued)
      (update $ :buffer #(mapv buffered %)))))

(defn ^:export failNextRollback
  "Makes the undo of the commits pending now in the open file fail if a
  rollback reverts them, so the rollback falls back to reloading the
  file. Pair it with `debug.holdSaves` to have commits pending.

  debug.holdSaves(); debug.commitInvalidChange(); debug.failNextRollback()"
  []
  (let [state   @st/state
        file-id (:current-file-id state)]
    (if (empty? (concat (:queue (:persistence state)) (:buffer (:persistence state))))
      "nothing is pending: hold the saves and commit something first"
      (do
        (st/emit!
         (ptk/reify ::fail-next-rollback
           ptk/UpdateEvent
           (update [_ state]
             ;; A change type with no handler, so applying it throws
             (update state :persistence pending-of-file file-id
                     #(update % :undo-changes (fn [changes]
                                                (into [{:type :debug-fail}] changes)))))))
        "the next rollback of the pending commits fails and reloads the file"))))

(defn- get-persistence-summary
  "What is pending in the tab and what could block the user."
  [state]
  (let [{:keys [status queue index buffer saved remote-changes]} (:persistence state)
        queued  (count (mapcat #(get-in index [% :commits]) queue))
        warn?   (contains? @st/ongoing-tasks :persistence)
        crash?  (some? (:exception state))]
    {:status        status
     :queued        queued
     :buffered      (count buffer)
     :saved         (count saved)
     :remoteChanges (count (mapcat val remote-changes))
     :leaveWarning  warn?
     :undoItems     (count (dm/get-in state [:workspace-undo :items]))
     :edition       (some-> (dm/get-in state [:workspace-local :edition]) str)
     :crashed       crash?
     :held          (some? (:hold @save-hook))
     :faults        (count (:faults @save-hook))
     :clean         (and (contains? #{nil :saved} status)
                         (zero? queued)
                         (empty? buffer)
                         (empty? saved)
                         (empty? remote-changes)
                         (not warn?)
                         (not crash?))}))

(defn ^:export persistenceState
  "The save state of the tab: what is pending, the leave-page warning, the
  undo history, the edited shape and a crash. `clean` is true when nothing
  is pending and nothing blocks the user."
  []
  (clj->js (get-persistence-summary @st/state)))

;; --- Invalid changes

(def ^:private invalid-change-kinds
  "Ways to break page objects that backend validation rejects, with the
  error code each raises."
  {"parent" {:code  :parent-not-found
             :break (fn [objects shape]
                      (assoc-in objects [(:id shape) :parent-id] (uuid/next)))}
   "child"  {:code  :child-not-found
             :break (fn [objects shape]
                      (update-in objects [(:parent-id shape) :shapes] conj (uuid/next)))}})

(defn- changed-ids
  [objects objects']
  (into [] (remove #(identical? (get objects %) (get objects' %))) (keys objects')))

(defn- renamed-main-instances
  "Main instances whose name differs between `objects` and `objects'`."
  [objects objects']
  (into []
        (comp (map #(get objects' %))
              (filter ctk/main-instance?)
              (remove #(= (:name %) (:name (get objects (:id %))))))
        (changed-ids objects objects')))

(defn- rename-components
  "Renames in `data` the components of the main instances renamed in
  `objects'`."
  [data objects objects']
  (reduce (fn [data {:keys [component-id name]}]
            (let [[path name] (cpn/split-group-name name)]
              (update-in data [:components component-id] assoc :path path :name name)))
          data
          (renamed-main-instances objects objects')))

(defn- objects-changes
  "Changes for the shapes that differ between `objects` and `objects'`."
  [origin page-id objects objects']
  (-> (pcb/empty-changes origin page-id)
      (pcb/with-objects objects)
      (pcb/update-shapes (changed-ids objects objects') #(get objects' (:id %)))))

(defn- commit-objects-change
  "Commits the shapes that differ between `objects` and `objects'`. A
  renamed main instance renames its component too, as validation requires.
  `params` go to `commit-changes`."
  [origin page-id data objects objects' params]
  (dwc/commit-changes
   (merge (reduce (fn [changes {:keys [component-id name]}]
                    (cll/generate-rename-component changes component-id name data))
                  (objects-changes origin page-id objects objects')
                  (renamed-main-instances objects objects'))
          params)))

(defn- objects-step
  "A step that commits the shapes `f` changes in the page objects, with
  `params` for `commit-changes`."
  ([f] (objects-step f nil))
  ([f params]
   (fn [origin page-id data objects]
     (let [objects' (f objects)]
       [(rename-components data objects objects')
        objects'
        (commit-objects-change origin page-id data objects objects' params)]))))

(defn- fault-step
  "A step that commits a marker the backend fails on with `fault`, with
  `params` for `commit-changes`."
  [fault params]
  (fn [origin _ data objects]
    [data
     objects
     ;; A real undo change, so the commit gets an undo entry as others do
     (dwc/commit-changes (merge {:origin origin
                                 :redo-changes [(save-fault-marker fault)]
                                 :undo-changes [(debug-plugin-data-change "save-fault-undo")]}
                                params))]))

(defn ^:export commitInvalidChange
  "Commits a change that the backend rejects, on the selected shape or the
  first one of the page, with optional valid renames before and after it,
  all in one save.

  Kinds: 'parent' (the shape points to a missing parent, the default) and
  'child' (its parent lists a missing child). With `fault` ('validation',
  'runtime', 'ex-info' or 'assertion') the bad commit changes nothing and
  the backend fails on it with that error instead (`debug-save-faults`
  backend flag). With `undo: false` the bad commit gets no undo entry, so
  rolling it back clears the whole undo history.

  debug.commitInvalidChange()
  debug.commitInvalidChange({kind: 'child', before: 2, after: 1})
  debug.commitInvalidChange({fault: 'runtime', before: 1})
  debug.commitInvalidChange({undo: false})"
  ([] (commitInvalidChange nil))
  ([options]
   (let [{:keys [kind fault before after undo]
          :or {kind "parent" before 0 after 0 undo true}} (js->clj options :keywordize-keys true)

         state    @st/state
         objects  (dsh/lookup-page-objects state)
         shape-id (or (first (dm/get-in state [:workspace-local :selected]))
                      (first (dm/get-in objects [uuid/zero :shapes])))
         shape    (get objects shape-id)
         {:keys [code break]} (get invalid-change-kinds kind)]

     (cond
       (nil? break)
       (str "unknown kind " kind ", expected one of "
            (str/join ", " (keys invalid-change-kinds)))

       (and (some? fault) (fault-error fault rejecting-faults))
       (fault-error fault rejecting-faults)

       (nil? shape)
       "select a shape, or open a page that has one"

       :else
       (let [rename (fn [label]
                      (objects-step #(assoc-in % [shape-id :name] (str (:name shape) " [" label "]"))))
             steps  (concat (map #(rename (str "before " (inc %))) (range before))
                            [(if fault
                               (fault-step fault {:save-undo? undo})
                               (objects-step #(break % shape) {:save-undo? undo}))]
                            (map #(rename (str "after " (inc %))) (range after)))]
         (st/emit!
          (ptk/reify ::commit-invalid-change
            ptk/WatchEvent
            (watch [it state _]
              (let [page-id (:current-page-id state)
                    commits (->> steps
                                 (reduce (fn [[data objects commits] step]
                                           (let [[data objects commit] (step it page-id data objects)]
                                             [data objects (conj commits commit)]))
                                         [(dsh/lookup-file-data state) (dsh/lookup-page-objects state) []])
                                 (last))]
                (rx/concat
                 (rx/from commits)
                 ;; Saves once all commits are buffered and the 200ms pending debounce passed
                 (->> (rx/timer 300)
                      (rx/map (constantly ::dps/force-persist))))))))
         (str "committed " (+ before 1 after) " changes on '" (:name shape)
              "'; commit " (inc before) " should fail with " (or fault (name code))))))))

(defn- broken-component-change
  "A change that points the component of `shape`, or the first one of the
  file, to a main instance that does not exist."
  [data shape]
  (when-let [component-id (or (:component-id shape)
                              (:id (first (ctkl/components-seq data))))]
    {:type :mod-component
     :id component-id
     :main-instance-id (uuid/next)}))

(defn ^:export saveBrokenData
  "Saves, skipping backend validation, data that fails it: the kinds of
  `debug.commitInvalidChange` on the selected shape or the first one of the
  page, or 'component' (the component of the selected shape, or the first
  of the file, points to a missing main instance). Then reloads the tab so
  it opens the broken file. Undo with `debug.repair()`.

  debug.saveBrokenData({kind: 'child'})
  debug.saveBrokenData({kind: 'component'})"
  ([] (saveBrokenData nil))
  ([options]
   (let [{:keys [kind] :or {kind "parent"}} (js->clj options :keywordize-keys true)
         state    @st/state
         file     (dsh/lookup-file state)
         page-id  (:current-page-id state)
         objects  (dsh/lookup-page-objects state)
         shape    (get objects (or (first (dm/get-in state [:workspace-local :selected]))
                                   (first (dm/get-in objects [uuid/zero :shapes]))))
         break    (get-in invalid-change-kinds [kind :break])
         changes  (cond
                    (= "component" kind)
                    (some-> (broken-component-change (:data file) shape) vector)

                    (and (some? break) (some? shape))
                    (:redo-changes (objects-changes nil page-id objects (break objects shape))))]
     (cond
       (not (:clean (get-persistence-summary state)))
       "wait until every change is saved"

       (empty? changes)
       (str "nothing to break: kinds are " (str/join ", " (keys invalid-change-kinds))
            " and component, and they need a shape or a component")

       :else
       (do
         (->> (rp/cmd! :update-file {:id (:id file)
                                     :revn (:revn file)
                                     :vern (:vern file)
                                     :session-id (:session-id state)
                                     :features (:features state)
                                     :changes changes
                                     :skip-validate true})
              (rx/subs! (fn [_] (dom/reload-current-window))
                        (fn [cause] (ex/print-throwable cause))))
         (str "saving broken " kind " data, then reloading"))))))

(defn ^:export network-averages
  []
  (.log js/console (clj->js @http/network-averages)))


(defn print-last-exception
  []
  (some-> errors/last-exception ex/print-throwable))


(defn ^:export dbg
  [o]
  (app.common.pprint/pprint o {:level 100 :length 100}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRUNE UNRELATED ITEMS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-related-ids
  "Given a `context` map (:pindex, :fdata) and a `[page-id id]` pair, returns
   the set of `[page-id id]` pairs directly related to it, NOT recursively:
   - its children (including itself)
   - its ancestors
   - the main instance `[page-id id]` of its component, if it's inside a
     component copy of a component defined in the current file (libraries
     are ignored)

   Ids are only unique within a page, so every id is always tracked together
   with the page it belongs to."
  [{:keys [pindex fdata]} [page-id id]]
  (let [page-objects (-> (get pindex page-id) :objects)
        shape        (get page-objects id)]
    (if (nil? shape)
      #{}
      (let [related   (-> #{}
                          (into (map (partial vector page-id) (cfh/get-parent-ids page-objects id)))
                          (into (map (partial vector page-id) (cfh/get-children-ids-with-self page-objects id))))
            head      (when (ctk/in-component-copy? shape)
                        (ctn/get-head-shape page-objects shape))
            component (when (and (some? head) (= (:component-file head) (:id fdata)))
                        (ctkl/get-component fdata (:component-id head)))]
        (cond-> related
          (some? component)
          (conj [(:main-instance-page component) (:main-instance-id component)]))))))

(defn ^:export prune-unrelated-items
  "This function is DESTRUCTIVE. It deletes from the current file all the pages and layers unrelated to the selection.
   It is used to isolate bugs"
  []
  (let [state       @st/state
        current-pid (:current-page-id state)
        selected    (get-selected state)

        fdata       (dsh/lookup-file-data state)
        pindex      (:pages-index fdata)

        context {:pindex pindex
                 :fdata  fdata}

        related
        (loop [related (into #{} (map (partial vector current-pid)) selected)]
          (let [expanded (->> related
                              (reduce (fn [acc pair] (into acc (get-related-ids context pair)))
                                      related)
                              (remove (fn [[_ id]] (= id uuid/zero)))
                              set)]
            (if (= expanded related)
              related
              (recur expanded))))

        related-by-page
        (reduce (fn [acc [page-id id]] (update acc page-id (fnil conj #{}) id))
                {}
                related)

        unrelated-pages
        (->> (:pages fdata)
             (remove (fn [page-id] (seq (get related-by-page page-id))))
             vec)

        unrelated-items-by-page
        (->> (:pages fdata)
             (remove (set unrelated-pages))
             (map (fn [page-id]
                    (let [page-related (get related-by-page page-id #{})
                          ids (->> (get pindex page-id)
                                   :objects
                                   keys
                                   (remove #{uuid/zero})
                                   (remove page-related)
                                   set)]
                      [page-id ids])))
             (filter (fn [[_ ids]] (seq ids)))
             vec)

        items-to-delete
        (reduce + (map (comp count second) unrelated-items-by-page))]

    (js/console.log (str "Pages to delete: " (count unrelated-pages)
                         ", items to delete: " items-to-delete))
    (.table js/console
            (->> unrelated-items-by-page
                 (mapcat (fn [[page-id ids]]
                           (let [objects (-> (get pindex page-id) :objects)]
                             (map (fn [id]
                                    {:page-id (str page-id)
                                     :id (str id)
                                     :name (:name (get objects id))})
                                  ids))))
                 (clj->js)))
    (when (and (or (seq unrelated-pages) (pos? items-to-delete))
               (js/confirm (str "Delete " (count unrelated-pages) " unrelated page(s) and "
                                items-to-delete " unrelated item(s)?")))
      (let [undo-id (js/Symbol)]
        (apply st/emit!
               (concat
                [(dwu/start-undo-transaction undo-id)]
                (map dw/delete-page unrelated-pages)
                (map (fn [[page-id ids]]
                       (dw/delete-shapes page-id ids))
                     unrelated-items-by-page)
                [(dwu/commit-undo-transaction undo-id)]))))
    nil))