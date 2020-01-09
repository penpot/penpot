;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.config :as cfg]
   [uxbox.main.constants :as c]
   [uxbox.main.data.icons :as udi]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.repo.core :as rp]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.websockets :as ws]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.data :refer [dissoc-in index-of]]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.perf :as perf]
   [uxbox.util.router :as rt]
   [uxbox.util.spec :as us]
   [uxbox.util.time :as dt]
   [uxbox.util.transit :as t]
   [uxbox.util.uuid :as uuid]
   [vendor.randomcolor]))

;; TODO: temporal workaround
(def clear-ruler nil)
(def start-ruler nil)

;; --- Specs

(s/def ::shape-attrs ::cp/shape-attrs)
(s/def ::set-of-uuid
  (s/every uuid? :kind set?))

;; --- Expose inner functions

(defn interrupt? [e] (= e :interrupt))

;; --- Protocols

(defprotocol IBatchedChange)

;; --- Declarations

(declare fetch-users)
(declare handle-who)
(declare handle-pointer-update)
(declare handle-pointer-send)
(declare handle-page-snapshot)
(declare shapes-changes-commited)
(declare commit-changes)
(declare commit-batched-changes)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websockets Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize WebSocket


(s/def ::type keyword?)
(s/def ::message
  (s/keys :req-un [::type]))

(defn initialize-ws
  [file-id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [uri (str "ws://localhost:6060/sub/" file-id)]
        (assoc-in state [:ws file-id] (ws/open uri))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [wsession (get-in state [:ws file-id])
            stoper (rx/filter #(= ::finalize-ws %) stream)]
        (->> (rx/merge
              (->> (ws/-stream wsession)
                   (rx/filter #(= :message (:type %)))
                   (rx/map (comp t/decode :payload))
                   (rx/filter #(s/valid? ::message %))
                   (rx/map (fn [{:keys [type] :as msg}]
                             (case type
                               :who (handle-who msg)
                               :pointer-update (handle-pointer-update msg)
                               :page-snapshot (handle-page-snapshot msg)
                               ::unknown))))

              (->> stream
                   (rx/filter ms/pointer-event?)
                   (rx/sample 150)
                   (rx/map #(handle-pointer-send file-id (:pt %)))))

             (rx/take-until stoper))))))

;; --- Finalize Websocket

(defn finalize-ws
  [file-id]
  (ptk/reify ::finalize-ws
    ptk/WatchEvent
    (watch [_ state stream]
      (ws/-close (get-in state [:ws file-id]))
      (rx/of ::finalize-ws))))

;; --- Handle: Who

;; TODO: assign color

(defn- assign-user-color
  [state user-id]
  (let [user (get-in state [:workspace-users :by-id user-id])
        color (js/randomcolor)
        user (if (string? (:color user))
               user
               (assoc user :color color))]
    (assoc-in state [:workspace-users :by-id user-id] user)))

(defn handle-who
  [{:keys [users] :as msg}]
  (s/assert set? users)
  (ptk/reify ::handle-who
    ptk/UpdateEvent
    (update [_ state]
      (as-> state $$
        (assoc-in $$ [:workspace-users :active] users)
        (reduce assign-user-color $$ users)))))

(defn handle-pointer-update
  [{:keys [user-id page-id x y] :as msg}]
  (ptk/reify ::handle-pointer-update
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-users :pointer user-id]
                {:page-id page-id
                 :user-id user-id
                 :x x
                 :y y}))))

(defn handle-pointer-send
  [file-id point]
  (ptk/reify ::handle-pointer-update
    ptk/EffectEvent
    (effect [_ state stream]
      (let [ws (get-in state [:ws file-id])
            pid (get-in state [:workspace-page :id])
            msg {:type :pointer-update
                 :page-id pid
                 :x (:x point)
                 :y (:y point)}]
        (ws/-send ws (t/encode msg))))))

(defn handle-page-snapshot
  [{:keys [user-id page-id version operations] :as msg}]
  (ptk/reify ::handle-page-snapshot
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:workspace-local state)]
        (when (= (:page-id local) page-id)
          (rx/of (shapes-changes-commited msg)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General workspace events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize Workspace

(declare initialize-alignment)

(def default-layout #{:sitemap :layers :element-options :rules})

(def workspace-default
  {:zoom 1
   :flags #{:sitemap :drawtools :layers :element-options :rules}
   :selected #{}
   :drawing nil
   :drawing-tool nil
   :tooltip nil})

(declare initialized)
(declare initialize-page)

(defn initialize
  "Initialize the workspace state."
  [file-id page-id]
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid page-id)
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:workspace-local state)]
        (if (not= (:file-id local) file-id)
          (rx/merge
           (rx/of (dp/fetch-file file-id)
                  (dp/fetch-pages file-id)
                  (fetch-users file-id))
           (->> (rx/zip (rx/filter (ptk/type? ::dp/pages-fetched) stream)
                        (rx/filter (ptk/type? ::dp/files-fetched) stream))
                (rx/take 1)
                (rx/do #(reset! st/loader false))
                (rx/mapcat #(rx/of (initialized file-id)
                                   (initialize-page page-id)
                                   #_(initialize-alignment page-id)))))
          (rx/merge
           (rx/of (initialize-page page-id))))))))

(defn- initialized
  [file-id]
  (s/assert ::us/uuid file-id)
  (ptk/reify ::initialized
    ptk/UpdateEvent
    (update [_ state]
      (let [file (get-in state [:files file-id])
            local (assoc workspace-default :file-id file-id)]
        (-> state
            (assoc :workspace-file file)
            (assoc :workspace-layout default-layout)
            (assoc :workspace-local local))))))

(defn finalize
  [file-id page-id]
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid page-id)
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state
              :workspace-page
              :workspace-data))))

(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:pages page-id])
            data (get-in state [:pages-data page-id])]
        (assoc state
               :workspace-data data
               :workspace-page page)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter #(or (ptk/type? ::finalize %)
                                   (ptk/type? ::initialize-page %))
                              stream)]
        (->> stream
             (rx/filter #(satisfies? IBatchedChange %))
             (rx/debounce 500)
             (rx/map (constantly commit-batched-changes))
             (rx/finalize #(prn "FINALIZE" %))
             (rx/take-until stoper))))))

;; --- Fetch Workspace Users

(declare users-fetched)

(defn fetch-users
  [file-id]
  (ptk/reify ::fetch-users
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :project-file-users {:file-id file-id})
           (rx/map users-fetched)))))

(defn users-fetched
  [users]
  (ptk/reify ::users-fetched
    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state user]
                (update-in state [:workspace-users :by-id (:id user)] merge user))
              state
              users))))

;; --- Toggle layout flag

(defn toggle-layout-flag
  [flag]
  (s/assert keyword? flag)
  (ptk/reify ::toggle-layout-flag
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [flags]
                (if (contains? flags flag)
                  (disj flags flag)
                  (conj flags flag)))))))

;; --- Workspace Flags

(defn activate-flag
   [flag]
  (s/assert keyword? flag)
  (ptk/reify ::activate-flag
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :flags]
                 (fn [flags]
                   (if (contains? flags flag)
                     flags
                     (conj flags flag)))))))

(defn deactivate-flag
  [flag]
  (s/assert keyword? flag)
  (ptk/reify ::deactivate-flag
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :flags] disj flag))))

(defn toggle-flag
  [flag]
  (s/assert keyword? flag)
  (ptk/reify ::toggle-flag
    ptk/WatchEvent
    (watch [_ state stream]
      (let [flags (get-in state [:workspace-local :flags])]
        (if (contains? flags flag)
          (rx/of (deactivate-flag flag))
          (rx/of (activate-flag flag)))))))

(defn set-tooltip
  [txt]
  ::todo)

;; --- Workspace Ruler

(defrecord ActivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of #_(set-tooltip "Drag to use the ruler")
           (activate-flag :ruler))))

(defn activate-ruler
  []
  (ActivateRuler.))

(defrecord DeactivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of #_(set-tooltip nil)
           (deactivate-flag :ruler))))

(defn deactivate-ruler
  []
  (DeactivateRuler.))

(defrecord ToggleRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [flags (get-in state [:workspace :flags])]
      (if (contains? flags :ruler)
        (rx/of (deactivate-ruler))
        (rx/of (activate-ruler))))))

(defn toggle-ruler
  []
  (ToggleRuler.))

;; --- Icons Toolbox

(defrecord SelectIconsToolboxCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :icons-toolbox] id))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (udi/fetch-icons id))))

(defn select-icons-toolbox-collection
  [id]
  {:pre [(or (nil? id) (uuid? id))]}
  (SelectIconsToolboxCollection. id))

(defrecord InitializeIconsToolbox []
  ptk/WatchEvent
  (watch [_ state stream]
    (letfn [(get-first-with-icons [colls]
              (->> (sort-by :name colls)
                   (filter #(> (:num-icons %) 0))
                   (first)
                   (:id)))
            (on-fetched [event]
              (let [coll (get-first-with-icons @event)]
                (select-icons-toolbox-collection coll)))]
      (rx/merge
       (rx/of (udi/fetch-collections)
              (udi/fetch-icons nil))

       ;; Only perform the autoselection if it is not
       ;; previously already selected by the user.
       ;; TODO
       #_(when-not (contains? (:workspace state) :icons-toolbox)
         (->> stream
              (rx/filter udi/collections-fetched?)
              (rx/take 1)
              (rx/map on-fetched)))))))

(defn initialize-icons-toolbox
  []
  (InitializeIconsToolbox.))

;; --- Clipboard Management

(defrecord CopyToClipboard []
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:workspace :selected])
          item {:id (uuid/random)
                :created-at (dt/now)
                :items selected}
          clipboard (-> (:clipboard state)
                        empty
                        (conj item))]
      (assoc state :clipboard
             (if (> (count clipboard) 5)
               (pop clipboard)
               clipboard)))))

(defn copy-to-clipboard
  "Copy selected shapes to clipboard."
  []
  (CopyToClipboard.))

(defrecord PasteFromClipboard [id]
  ptk/UpdateEvent
  (update [_ state]
    state
    #_(let [page-id (get-in state [:workspace :page :id])
          selected (if (nil? id)
                     (first (:clipboard state))
                     (->> (:clipboard state)
                          (filter #(= id (:id %)))
                          (first)))]
      (ds/duplicate-shapes state (:items selected) page-id))))

(defn paste-from-clipboard
  "Copy selected shapes to clipboard."
  ([] (PasteFromClipboard. nil))
  ([id] (PasteFromClipboard. id)))

;; --- Zoom Management

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(nth c/zoom-levels
                           (+ (index-of c/zoom-levels %) 1)
                           (last c/zoom-levels))]
        (update-in state [:workspace-local :zoom] (fnil increase 1))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(nth c/zoom-levels
                           (- (index-of c/zoom-levels %) 1)
                           (first c/zoom-levels))]
        (update-in state [:workspace-local :zoom] (fnil decrease 1))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :zoom] 1))))

;; --- Grid Alignment

(defn initialize-alignment
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::initialize-alignment
    ptk/WatchEvent
    (watch [_ state stream]
      (let [metadata (get-in state [:workspace-page :metadata])
            params {:width c/viewport-width
                    :height c/viewport-height
                    :x-axis (:grid-x-axis metadata c/grid-x-axis)
                    :y-axis (:grid-y-axis metadata c/grid-y-axis)}]
        (rx/concat
         (rx/of (deactivate-flag :grid-indexed))
         (->> (uwrk/initialize-alignment params)
              (rx/map #(activate-flag :grid-indexed))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Add shape to Workspace

(defn impl-retrieve-used-names
  "Returns a set of already used names by shapes
  in the current workspace page."
  [state]
  (let [data (:workspace-data state)]
    (into #{} (map :name) (vals (:shapes-by-id data)))))

(defn impl-generate-unique-name
  "A unique name generator based on the current workspace page."
  [state basename]
  (let [used (impl-retrieve-used-names state)]
    (loop [counter 1]
      (let [candidate (str basename "-" counter)]
        (if (contains? used candidate)
          (recur (inc counter))
          candidate)))))

(defn impl-assoc-shape
  [state {:keys [id] :as data}]
  (let [name (impl-generate-unique-name state (:name data))
        shape (assoc data :name name)]
    (as-> state $
      (if (= :canvas (:type shape))
        (update-in $ [:workspace-data :canvas] conj id)
        (update-in $ [:workspace-data :shapes] conj id))
      (assoc-in $ [:workspace-data :shapes-by-id id] shape))))

(declare select-shape)
(declare recalculate-shape-canvas-relation)

(def shape-default-attrs
  {:stroke-color "#000000"
   :stroke-opacity 1
   :fill-color "#000000"
   :fill-opacity 1})

(defn add-shape
  [data]
  (s/assert ::shape-attrs data)
  (let [id (uuid/random)]
    (ptk/reify ::add-shape
      ptk/UpdateEvent
      (update [_ state]
        (let [shape (-> (geom/setup-proportions data)
                        (assoc :id id))
              shape (merge shape-default-attrs shape)
              shape (recalculate-shape-canvas-relation state shape)]
          (impl-assoc-shape state shape)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [shape (get-in state [:workspace-data :shapes-by-id id])]
          (rx/of (commit-changes [[:add-shape id shape]])
                 (select-shape id)))))))

(def canvas-default-attrs
  {:stroke-color "#000000"
   :stroke-opacity 1
   :fill-color "#ffffff"
   :fill-opacity 1})

(defn add-canvas
  [data]
  (s/assert ::shape-attrs data)
  (let [id (uuid/random)]
    (ptk/reify ::add-canvas
      ptk/UpdateEvent
      (update [_ state]
        (let [shape (-> (geom/setup-proportions data)
                        (assoc :id id))
              shape (merge canvas-default-attrs shape)
              shape (recalculate-shape-canvas-relation state shape)]
          (impl-assoc-shape state shape)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [shape (get-in state [:workspace-data :shapes-by-id id])]
          (rx/of (commit-changes [[:add-canvas id shape]])
                 (select-shape id)))))))


;; --- Duplicate Selected

(defn impl-duplicate-shape
  [state id]
  (let [shape (get-in state [:workspace-data :shapes-by-id id])]
    (assoc shape :id (uuid/random))))

(def duplicate-selected
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected  (get-in state [:workspace-local :selected])
            duplicate (partial impl-duplicate-shape state)
            shapes    (map duplicate selected)]
        (rx/merge
         (rx/from (map (fn [s] #(impl-assoc-shape % s)) shapes))
         (rx/of (commit-changes (mapv #(vector :add-shape (:id %) %) shapes))))))))

;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::select-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :selected]
                 (fn [selected]
                   (if (contains? selected id)
                     (disj selected id)
                     (conj selected id)))))

    ptk/WatchEvent
    (watch [_ state s]
      (rx/of (activate-flag :element-options)))))

(def deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local #(-> %
                                          (assoc :selected #{})
                                          (dissoc :selected-canvas))))))

;; --- Select Shapes (By selrect)

(defn- impl-try-match-shape
  [xf selrect acc {:keys [type id items] :as shape}]
  (cond
    (geom/contained-in? shape selrect)
    (conj acc id)

    (geom/overlaps? shape selrect)
    (conj acc id)

    :else
    acc))

(defn impl-match-by-selrect
  [state selrect]
  (let [data (:workspace-data state)
        xf (comp (map #(get-in data [:shapes-by-id %]))
                 (remove :hidden)
                 (remove :blocked)
                 (remove #(= :canvas (:type %)))
                 (map geom/selection-rect))
        match (partial impl-try-match-shape xf selrect)
        shapes (:shapes data)]
    (reduce match #{} (sequence xf shapes))))

(def select-shapes-by-current-selrect
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [selrect id]} (:workspace-local state)]
        (->> (impl-match-by-selrect state selrect)
             (assoc-in state [:workspace-local :selected]))))))

;; --- Update Shape Attrs

(defn update-shape
  [id attrs]
  (s/assert ::us/uuid id)
  (s/assert ::shape-attrs attrs)
  (ptk/reify ::update-shape
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [shape-old (get-in state [:workspace-data :shapes-by-id id])
            shape-new (merge shape-old attrs)
            diff (d/diff-maps shape-old shape-new)]
        (-> state
            (assoc-in [:workspace-data :shapes-by-id id] shape-new)
            (update ::batched-changes (fnil conj []) (into [:mod-shape id] diff)))))))

;; --- Update Page Options

(defn update-options
  [opts]
  (s/assert ::cp/options opts)
  (ptk/reify ::update-options
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [opts-old (get-in state [:workspace-data :options])
            opts-new (merge opts-old opts)
            diff (d/diff-maps opts-old opts-new)]
        (-> state
            (assoc-in [:workspace-data :options] opts-new)
            (update ::batched-changes (fnil conj []) (into [:mod-opts] diff)))))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [& attrs]
  (ptk/reify ::update-selected-shapes-attrs
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/from (map #(apply update-shape % attrs) selected))))))

;; --- Shape Movement (using keyboard shorcuts)

(declare initial-selection-align)
(declare apply-temporal-displacement-in-bulk)
(declare materialize-temporal-modifier-in-bulk)

(defn- get-displacement-with-grid
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction options]
  (let [grid-x (:grid-x options 10)
        grid-y (:grid-y options 10)
        x-mod (mod (:x shape) grid-x)
        y-mod (mod (:y shape) grid-y)]
    (case direction
      :up (gpt/point 0 (- (if (zero? y-mod) grid-y y-mod)))
      :down (gpt/point 0 (- grid-y y-mod))
      :left (gpt/point (- (if (zero? x-mod) grid-x x-mod)) 0)
      :right (gpt/point (- grid-x x-mod) 0))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(s/def ::direction #{:up :down :right :left})

(defn move-selected
  [direction align?]
  (s/assert ::direction direction)
  (s/assert boolean? align?)

  (ptk/reify ::move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            options (get-in state [:workspace-data :options])
            shapes (map #(get-in state [:workspace-data :shapes-by-id %]) selected)
            shape (geom/shapes->rect-shape shapes)
            displacement (if align?
                           (get-displacement-with-grid shape direction options)
                           (get-displacement shape direction))]
        (rx/of (apply-temporal-displacement-in-bulk selected displacement)
               (materialize-temporal-modifier-in-bulk selected))))))

;; --- Delete Selected

(defn impl-dissoc-shape
  "Given a shape, removes it from the state."
  [state {:keys [id type] :as shape}]
  (as-> state $$
    (if (= :canvas type)
      (update-in $$ [:workspace-data :canvas]
                 (fn [items] (vec (remove #(= % id) items))))
      (update-in $$ [:workspace-data :shapes]
                 (fn [items] (vec (remove #(= % id) items)))))
    (update-in $$ [:workspace-data :shapes-by-id] dissoc id)))

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace-local :selected])]
        (reduce impl-dissoc-shape state
                (map #(get-in state [:workspace-data :shapes-by-id %]) selected))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/of (commit-changes (mapv #(vector :del-shape %) selected)))))))

;; --- Rename Shape

(defn rename-shape
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-shape
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes id :name] name))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (commit-changes [[:mod-shape id [:mod :name name]]])))))

;; --- Shape Vertical Ordering

(declare impl-order-shape)

(defn order-selected-shapes
  [loc]
  (s/assert ::direction loc)
  (ptk/reify ::move-selected-layer
    ptk/UpdateEvent
    (update [_ state]
      (let [id (first (get-in state [:workspace-local :selected]))
            type (get-in state [:workspace-data :shapes-by-id id :type])]
        ;; NOTE: multiple selection ordering not supported
        (if (and id (not= type :canvas))
          (impl-order-shape state id loc)
          state)))))

(defn impl-order-shape
  [state sid opt]
  (let [shapes (get-in state [:workspace-data :shapes])
        index (case opt
                :top 0
                :down (min (- (count shapes) 1) (inc (index-of shapes sid)))
                :up (max 0 (- (index-of shapes sid) 1))
                :bottom (- (count shapes) 1))]
    (update-in state [:workspace-data :shapes]
               (fn [items]
                 (let [[fst snd] (->> (remove #(= % sid) items)
                                      (split-at index))]
                   (into [] (concat fst [sid] snd)))))))

;; --- Change Shape Order (D&D Ordering)

(defn temporal-shape-order-change
  [id index]
  (s/assert ::us/uuid id)
  (s/assert number? index)
  (ptk/reify ::change-shape-order
    ptk/UpdateEvent
    (update [_ state]
      (let [shapes (get-in state [:workspace-data :shapes])
            shapes (into [] (remove #(= % id)) shapes)
            [before after] (split-at index shapes)
            shapes (d/concat [] before [id] after)
            change [:mov-shape id :after (last before)]]
        (-> state
            (assoc-in [:workspace-data :shapes] shapes)
            (assoc ::tmp-shape-order-change change))))))

(def commit-shape-order-change
  (ptk/reify ::commit-shape-order-change
    ptk/WatchEvent
    (watch [_ state stream]
      (let [change (::tmp-shape-order-change state)]
        (rx/of #(dissoc state ::tmp-changes)
               (commit-changes [change]))))))

;; --- Change Canvas Order (D&D Ordering)

(defn change-canvas-order
  [{:keys [id index] :as params}]
  (s/assert ::us/uuid id)
  (s/assert ::us/number index)
  (ptk/reify ::change-canvas-order
    ptk/UpdateEvent
    (update [_ state]
      (let [shapes (get-in state [:workspace-data :canvas])
            shapes (into [] (remove #(= % id)) shapes)
            [before after] (split-at index shapes)
            shapes (vec (concat before [id] after))]
        (assoc-in state [:workspace-data :canvas] shapes)))))

;; --- Shape / Selection Alignment

(defn initial-selection-align
  "Align the selection of shapes."
  [ids]
  (s/assert ::set-of-uuid ids)
  (ptk/reify ::initialize-shapes-align-in-bulk
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shapes-by-id (get-in state [:workspace-data :shapes-by-id])
            shapes (mapv #(get shapes-by-id %) ids)
            sshape (geom/shapes->rect-shape shapes)
            point (gpt/point (:x1 sshape)
                             (:y1 sshape))]
        (->> (uwrk/align-point point)
             (rx/map (fn [{:keys [x y] :as pt}]
                       (apply-temporal-displacement-in-bulk ids (gpt/subtract pt point)))))))))

;; --- Temportal displacement for Shape / Selection

(defn assoc-temporal-modifier-in-bulk
  [ids xfmt]
  (s/assert ::set-of-uuid ids)
  (s/assert gmt/matrix? xfmt)
  (ptk/reify ::assoc-temporal-modifier-in-bulk
    ptk/UpdateEvent
    (update [_ state]
      (reduce #(assoc-in %1 [:workspace-data :shapes-by-id %2 :modifier-mtx] xfmt) state ids))))

(defn apply-temporal-displacement-in-bulk
  "Apply the same displacement delta to all shapes identified by the
  set if ids."
  [ids delta]
  (s/assert ::set-of-uuid ids)
  (s/assert gpt/point? delta)
  (letfn [(process-shape [state id]
            (let [prev (get-in state [:workspace-data :shapes-by-id id :modifier-mtx] (gmt/matrix))
                  xfmt (gmt/translate prev delta)]
              (assoc-in state [:workspace-data :shapes-by-id id :modifier-mtx] xfmt)))]
    (ptk/reify ::apply-temporal-displacement-in-bulk
      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids)))))

(defn- recalculate-shape-canvas-relation
  [state shape]
  (let [shape' (geom/shape->rect-shape shape)
        xfmt (comp (map #(get-in state [:workspace-data :shapes-by-id %]))
                   (map geom/shape->rect-shape)
                   (filter #(geom/overlaps? % shape'))
                   (map :id))

        id (->> (get-in state [:workspace-data :canvas])
                (into [] xfmt)
                (first))]
    (assoc shape :canvas id)))

(defn materialize-temporal-modifier-in-bulk
  [ids]
  (letfn [(process-shape [state id]
            (let [shape (get-in state [:workspace-data :shapes-by-id id])
                  xfmt (or (:modifier-mtx shape) (gmt/matrix))
                  shape-old (dissoc shape :modifier-mtx)
                  shape-new (geom/transform shape-old xfmt)
                  shape-new (recalculate-shape-canvas-relation state shape-new)
                  diff (d/diff-maps shape-old shape-new)]
              (-> state
                  (assoc-in [:workspace-data :shapes-by-id id] shape-new)
                  (update ::batched-changes (fnil conj []) (into [:mod-shape id] diff)))))]

    (ptk/reify ::materialize-temporal-modifier-in-bulk
      IBatchedChange
      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids)))))

(defn commit-changes
  [changes]
  (s/assert ::cp/changes changes)
  (ptk/reify ::commit-changes
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace-local :page-id])
            data (get-in state [:pages-data pid])]
        (update-in state [:pages-data pid] cp/process-changes changes)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page (:workspace-page state)
            params {:id (:id page)
                    :version (:version page)
                    :changes changes}]
        (->> (rp/mutation :update-project-page params)
             (rx/map shapes-changes-commited))))))

(def commit-batched-changes
  (ptk/reify ::commit-batched-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [changes (::batched-changes state)]
        (rx/of #(dissoc % ::batched-changes)
               (commit-changes changes))))))

(s/def ::shapes-changes-commited
  (s/keys :req-un [::page-id ::version ::cp/changes]))

(defn shapes-changes-commited
  [{:keys [page-id version changes] :as params}]
  (s/assert ::shapes-changes-commited params)
  (ptk/reify ::shapes-changes-commited
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-page :version] version)
          (assoc-in [:pages page-id :version] version)
          (update-in [:pages-data page-id] cp/process-changes changes)
          (update :workspace-data cp/process-changes changes)))))

;; --- Start shape "edition mode"

(defn start-edition-mode
  [id]
  {:pre [(uuid? id)]}
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :edition] id))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter #(= % :interrupt))
           (rx/take 1)
           (rx/map (fn [_] #(dissoc-in % [:workspace-local :edition])))))))

;; --- Select for Drawing

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :drawing-tool :drawing))))

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (ptk/reify ::select-for-drawing
     ptk/UpdateEvent
     (update [_ state]
       (update state :workspace-local assoc :drawing-tool tool :drawing data))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [cancel-event? (fn [event]
                             (interrupt? event))
             stoper (rx/filter (ptk/type? ::clear-drawing) stream)]
         (->> (rx/filter cancel-event? stream)
              (rx/take 1)
              (rx/map (constantly clear-drawing))
              (rx/take-until stoper)))))))

;; --- Update Dimensions

(s/def ::width ::us/number)
(s/def ::height ::us/number)

(s/def ::update-dimensions
  (s/keys :opt-un [::width ::height]))

;; TODO: emit commit-changes

(defn update-dimensions
  "A helper event just for update the position
  of the shape using the width and height attrs
  instread final point of coordinates."
  [id dimensions]
  (s/assert ::us/uuid id)
  (s/assert ::update-dimensions dimensions)
  (ptk/reify ::update-dimensions
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :shapes-by-id id] geom/resize-dim dimensions))))

;; --- Shape Proportions

(defn toggle-shape-proportion-lock
  [id]
  (ptk/reify ::toggle-shape-proportion-lock
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:workspace-data :shapes-by-id id])]
        (if (:proportion-lock shape)
          (assoc-in state [:workspace-data :shapes-by-id id :proportion-lock] false)
          (->> (geom/assign-proportions (assoc shape :proportion-lock true))
               (assoc-in state [:workspace-data :shapes-by-id id])))))))

;; --- Update Shape Position

(defn update-position
  [id point]
  (s/assert ::us/uuid id)
  (s/assert gpt/point? point)
  (ptk/reify ::update-position
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :shapes-by-id id] geom/absolute-move point))))

;; --- Path Modifications

;; TODO: revisit
(deftype UpdatePath [id index delta]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace-data :shapes-by-id id :segments index] gpt/add delta)))

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  {:pre [(uuid? id) (number? index) (gpt/point? delta)]}
  (UpdatePath. id index delta))

;; --- Initial Path Point Alignment

;; TODO: revisit
(deftype InitialPathPointAlign [id index]
  ptk/WatchEvent
  (watch [_ state s]
    (let [shape (get-in state [:workspace-data :shapes-by-id id])
          point (get-in shape [:segments index])]
      (->> (uwrk/align-point point)
           (rx/map #(update-path id index %))))))

(defn initial-path-point-align
  "Event responsible of align a specified point of the
  shape by `index` with the grid."
  [id index]
  {:pre [(uuid? id)
         (number? index)
         (not (neg? index))]}
  (InitialPathPointAlign. id index))

;; --- Shape Visibility

;; TODO: revisit
(defn set-hidden-attr
  [id value]
  (s/assert ::us/uuid id)
  (s/assert ::us/boolean value)
  (letfn [(impl-set-hidden [state id]
            (let [{:keys [type] :as shape} (get-in state [:shapes id])]
              (as-> state $
                (assoc-in $ [:shapes id :hidden] value)
                (if (= :canvas type)
                  (let [shapes (get-in state [:pages (:page shape) :shapes])
                        xform (comp (map #(get-in state [:shapes %]))
                                    (filter #(= id (:canvas %)))
                                    (map :id))]
                    (reduce impl-set-hidden $ (sequence xform shapes)))
                  $))))]
    (ptk/reify ::set-hidden-attr
      ptk/UpdateEvent
      (update [_ state]
        (impl-set-hidden state id)))))

;; --- Shape Blocking

;; TODO: revisit
(defn set-blocked-attr
  [id value]
  (s/assert ::us/uuid id)
  (s/assert ::us/boolean value)
  (letfn [(impl-set-blocked [state id]
            (let [{:keys [type] :as shape} (get-in state [:shapes id])]
              (as-> state $
                (assoc-in $ [:shapes id :blocked] value)
                (if (= :canvas type)
                  (let [shapes (get-in state [:pages (:page shape) :shapes])
                        xform (comp (map #(get-in state [:shapes %]))
                                    (filter #(= id (:canvas %)))
                                    (map :id))]
                    (reduce impl-set-blocked $ (sequence xform shapes)))
                  $))))]
    (ptk/reify ::set-blocked-attr
      ptk/UpdateEvent
      (update [_ state]
        (impl-set-blocked state id)))))

;; --- Shape Locking

;; TODO: revisit
(deftype LockShape [id]
  ptk/UpdateEvent
  (update [_ state]
    (letfn [(mark-locked [state id]
              (let [shape (get-in state [:shapes id])]
                (if (= :group (:type shape))
                  (as-> state $
                    (assoc-in $ [:shapes id :locked] true)
                    (reduce mark-locked $ (:items shape)))
                  (assoc-in state [:shapes id :locked] true))))]
      (mark-locked state id))))

;; TODO: revisit
(defn lock-shape
  [id]
  {:pre [(uuid? id)]}
  (LockShape. id))

(deftype UnlockShape [id]
  ptk/UpdateEvent
  (update [_ state]
    (letfn [(mark-unlocked [state id]
              (let [shape (get-in state [:shapes id])]
                (if (= :group (:type shape))
                  (as-> state $
                    (assoc-in $ [:shapes id :locked] false)
                    (reduce mark-unlocked $ (:items shape)))
                  (assoc-in state [:shapes id :locked] false))))]
      (mark-unlocked state id))))

;; TODO: revisit
(defn unlock-shape
  [id]
  {:pre [(uuid? id)]}
  (UnlockShape. id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canvas Interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn select-canvas
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::select-canvas
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local assoc :selected-canvas id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn navigate-to-project
  [project-id]
  (ptk/reify ::navigate-to-project
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects project-id :pages])
            params {:project project-id :page (first page-ids)}]
        (rx/of (rt/nav :workspace/page params))))))

(defn go-to-page
  [page-id]
  (s/assert ::us/uuid page-id)
  (ptk/reify ::go-to
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file-id (get-in state [:workspace-local :file-id])
            path-params {:file-id file-id}
            query-params {:page-id page-id}]
        (rx/of (rt/nav :workspace path-params query-params))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Changes Reactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Change Page Order (D&D Ordering)

(defn change-page-order
  [{:keys [id index] :as params}]
  {:pre [(uuid? id) (number? index)]}
  (ptk/reify ::change-page-order
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:pages id])
            pages (get-in state [:projects (:project-id page) :pages])
            pages (into [] (remove #(= % id)) pages)
            [before after] (split-at index pages)
            pages (vec (concat before [id] after))]
        (assoc-in state [:projects (:project-id page) :pages] pages)))))

