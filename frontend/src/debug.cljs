;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns debug
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.transit :as t]
   [app.common.types.file :as ctf]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.preview :as dp]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shortcuts]
   [app.main.errors :as errors]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [promesa.core :as p]))

(l/set-level! :debug)

(defn ^:export set-logging
  ([level]
   (l/set-level! :app (keyword level)))
  ([ns level]
   (l/set-level! (keyword ns) (keyword level))))

;; These events are excluded when we activate the :events flag
(def debug-exclude-events
  #{:app.main.data.workspace.notifications/handle-pointer-update
    :app.main.data.workspace.notifications/handle-pointer-send
    :app.main.data.workspace.persistence/update-persistence-status
    :app.main.data.workspace.changes/update-indices
    :app.main.data.websocket/send-message
    :app.main.data.workspace.selection/change-hover-state})

(defn- enable!
  [option]
  (dbg/enable! option)
  (when (= :events option)
    (set! st/*debug-events* true))
  (js* "app.main.reinit()"))

(defn- disable!
  [option]
  (dbg/disable! option)
  (when (= :events option)
    (set! st/*debug-events* false))
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

(defn prettify
  "Prepare x for cleaner output when logged."
  [x]
  (cond
    (map? x) (d/mapm #(prettify %2) x)
    (vector? x) (mapv prettify x)
    (seq? x) (map prettify x)
    (set? x) (into #{} (map prettify) x)
    (number? x) (mth/precision x 4)
    (uuid? x) (str/concat "#uuid " x)
    :else x))

(defn ^:export logjs
  ([str] (tap (partial logjs str)))
  ([str val]
   (js/console.log str (clj->js (prettify val) :keyword-fn (fn [v] (str/concat v))))
   val))

(when (exists? js/window)
  (set! (.-dbg ^js js/window) clj->js)
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

(defn ^:export fps
  "Adds a widget to keep track of the average FPS's"
  []
  (let [last (volatile! (.now js/performance))
        avg  (volatile! 0)
        node (-> (.createElement js/document "div")
                 (obj/set! "id" "fps")
                 (obj/set! "style" widget-style))
        body (obj/get js/document "body")

        do-thing (fn do-thing []
                   (timers/raf
                    (fn []
                      (let [cur (.now js/performance)
                            ts (/ 1000 (* (- cur @last)))
                            val (+ @avg (* (- ts @avg) 0.1))]

                        (obj/set! node "innerText" val)
                        (vreset! last cur)
                        (vreset! avg val)
                        (do-thing)))))]

    (.appendChild body node)
    (do-thing)))

(defn ^:export dump-state []
  (logjs "state" @st/state)
  nil)

(defn ^:export dump-data []
  (logjs "workspace-data" (get @st/state :workspace-data))
  nil)

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
  (let [page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])]
    (logjs "objects" objects)
    nil))

(defn ^:export dump-objects
  []
  (dump-objects' @st/state))

(defn dump-object'
  [state name]
  (let [page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])
        result  (or (d/seek (fn [[_ shape]] (= name (:name shape))) objects)
                    (get objects (uuid/uuid name)))]
    (logjs name result)
    nil))

(defn ^:export dump-object
  [name]
  (dump-object' @st/state name))

(defn dump-selected'
  [state]
  (let [page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])
        selected (get-in state [:workspace-local :selected])
        result (->> selected (map (d/getf objects)))]
    (logjs "selected" result)
    nil))

(defn ^:export dump-selected
  []
  (dump-selected' @st/state))

(defn ^:export preview-selected
  []
  (st/emit! (dp/open-preview-selected)))

(defn ^:export parent
  []
  (let [state @st/state
        page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])
        selected (first (get-in state [:workspace-local :selected]))
        parent-id (get-in objects [selected :parent-id])
        parent (get objects parent-id)]
    (when parent
      (prn (str (:name parent) " - " (:id parent))))
    nil))

(defn ^:export frame
  []
  (let [state @st/state
        page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])
        selected (first (get-in state [:workspace-local :selected]))
        frame-id (get-in objects [selected :frame-id])
        frame (get objects frame-id)]
    (when frame
      (prn (str (:name frame) " - " (:id frame))))
    nil))

(defn ^:export select-by-id
  [shape-id]
  (st/emit! (dws/select-shape (uuid/uuid shape-id))))

(defn dump-tree'
  ([state] (dump-tree' state false false false))
  ([state show-ids] (dump-tree' state show-ids false false))
  ([state show-ids show-touched] (dump-tree' state show-ids show-touched false))
  ([state show-ids show-touched show-modified]
   (let [page-id    (get state :current-page-id)
         file       (assoc (get state :workspace-file)
                           :data (get state :workspace-data))
         libraries  (get state :workspace-libraries)]
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
         file       (assoc (get state :workspace-file)
                           :data (get state :workspace-data))
         libraries  (get state :workspace-libraries)]
     (ctf/dump-subtree file page-id shape-id libraries {:show-ids show-ids
                                                        :show-touched show-touched
                                                        :show-modified show-modified}))))
(defn ^:export dump-subtree
  ([shape-id] (dump-subtree' @st/state (uuid/uuid shape-id)))
  ([shape-id show-ids] (dump-subtree' @st/state (uuid/uuid shape-id) show-ids false false))
  ([shape-id show-ids show-touched] (dump-subtree' @st/state (uuid/uuid shape-id) show-ids show-touched false))
  ([shape-id show-ids show-touched show-modified] (dump-subtree' @st/state (uuid/uuid shape-id) show-ids show-touched show-modified)))

(when *assert*
  (defonce debug-subscription
    (->> st/stream
         (rx/filter ptk/event?)
         (rx/filter (fn [s] (and (dbg/enabled? :events)
                                 (not (debug-exclude-events (ptk/type s))))))
         (rx/subs #(println "[stream]: " (ptk/repr-event %))))))

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
  (let [page-id (get @st/state :current-page-id)
        objects (get-in @st/state [:workspace-data :pages-index page-id :objects])]
    (.log js/console (modif->js (:workspace-modifiers @st/state) objects)))
  nil)

(defn ^:export set-workspace-read-only
  [read-only?]
  (st/emit! (dw/set-workspace-read-only read-only?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REPAIR & VALIDATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Validation and repair

(defn ^:export validate
  ([] (validate nil))
  ([shape-id]
   (let [file      (assoc (get @st/state :workspace-file)
                          :data (get @st/state :workspace-data))
         libraries (get @st/state :workspace-libraries)]

     (try
       (->> (if shape-id
              (let [page (dm/get-in file [:data :pages-index (get @st/state :current-page-id)])]
                (cfv/validate-shape (uuid shape-id) file page libraries))
              (cfv/validate-file file libraries))
            (group-by :code)
            (clj->js))
       (catch :default cause
         (errors/print-error! cause))))))

(defn ^:export repair
  []
  (let [file      (assoc (get @st/state :workspace-file)
                         :data (get @st/state :workspace-data))
        libraries (get @st/state :workspace-libraries)
        errors    (cfv/validate-file file libraries)]

    (l/dbg :hint "repair current file" :errors (count errors))

    (st/emit!
     (ptk/reify ::repair-current-file
       ptk/WatchEvent
       (watch [_ state _]
         (let [features  (features/get-team-enabled-features state)
               sid       (:session-id state)
               file      (get state :workspace-file)
               file-data (get state :workspace-data)
               libraries (get state :workspace-libraries)

               changes   (-> (cfr/repair-file file-data libraries errors)
                             (get :redo-changes))

               params    {:id (:id file)
                          :revn (:revn file)
                          :session-id sid
                          :changes changes
                          :features features
                          :skip-validate true}]

           (->> (rp/cmd! :update-file params)
                (rx/tap #(dom/reload-current-window)))))))))

(defn ^:export fix-orphan-shapes
  []
  (st/emit! (dw/fix-orphan-shapes)))

(defn ^:export find-components-norefs
  []
  (st/emit! (dw/find-components-norefs)))

(defn ^:export set-shape-ref
  [id shape-ref]
  (st/emit! (dw/set-shape-ref id shape-ref)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SNAPSHOTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:export list-available-snapshots
  [file-id]
  (let [file-id (or (d/parse-uuid file-id)
                    (:current-file-id @st/state))]
    (->> (http/send! {:method :get
                      :uri (u/join cf/public-uri "api/rpc/command/get-file-snapshots")
                      :query {:file-id file-id}})
         (rx/map http/conditional-decode-transit)
         (rx/mapcat rp/handle-response)
         (rx/subs (fn [result]
                    (let [result (map (fn [row]
                                        (update row :id str))
                                      result)]
                      (js/console.table (clj->js result))))
                  (fn [cause]
                    (js/console.log "EE:" cause))))
    nil))

(defn ^:export take-snapshot
  [label file-id]
  (when-let [file-id (or (d/parse-uuid file-id)
                         (:current-file-id @st/state))]
    (->> (http/send! {:method :post
                      :uri (u/join cf/public-uri "api/rpc/command/take-file-snapshot")
                      :body (http/transit-data {:file-id file-id :label label})})
         (rx/map http/conditional-decode-transit)
         (rx/mapcat rp/handle-response)
         (rx/subs (fn [{:keys [id]}]
                    (println "Snapshot saved:" (str id)))
                  (fn [cause]
                    (js/console.log "EE:" cause))))))

(defn ^:export restore-snapshot
  [id file-id]
  (when-let [file-id (or (d/parse-uuid file-id)
                         (:current-file-id @st/state))]
    (when-let [id (d/parse-uuid id)]
      (->> (http/send! {:method :post
                        :uri (u/join cf/public-uri "api/rpc/command/restore-file-snapshot")
                        :body (http/transit-data {:file-id file-id :id id})})
           (rx/map http/conditional-decode-transit)
           (rx/mapcat rp/handle-response)
           (rx/subs (fn [_]
                      (println "Snapshot restored " id)
                      #_(.reload js/location))
                    (fn [cause]
                      (js/console.log "EE:" cause)))))))
