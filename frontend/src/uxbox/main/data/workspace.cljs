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
   [uxbox.config :as cfg]
   [uxbox.main.constants :as c]
   [uxbox.main.data.icons :as udi]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.repo.core :as rp]
   [uxbox.main.store :as st]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.data :refer [dissoc-in index-of]]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.perf :as perf]
   [uxbox.util.router :as rt]
   [uxbox.util.spec :as us]
   [uxbox.util.time :as dt]
   [uxbox.util.uuid :as uuid]))

;; TODO: temporal workaround
(def clear-ruler nil)
(def start-ruler nil)

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::blocked boolean?)
(s/def ::collapsed boolean?)
(s/def ::content string?)
(s/def ::fill-color string?)
(s/def ::fill-opacity number?)
(s/def ::font-family string?)
(s/def ::font-size number?)
(s/def ::font-style string?)
(s/def ::font-weight string?)
(s/def ::height number?)
(s/def ::hidden boolean?)
(s/def ::id uuid?)
(s/def ::letter-spacing number?)
(s/def ::line-height number?)
(s/def ::locked boolean?)
(s/def ::name string?)
(s/def ::page uuid?)
(s/def ::proportion number?)
(s/def ::proportion-lock boolean?)
(s/def ::rx number?)
(s/def ::ry number?)
(s/def ::stroke-color string?)
(s/def ::stroke-opacity number?)
(s/def ::stroke-style #{:none :solid :dotted :dashed :mixed})
(s/def ::stroke-width number?)
(s/def ::text-align #{"left" "right" "center" "justify"})
(s/def ::type #{:rect :path :circle :image :text})
(s/def ::width number?)
(s/def ::x1 number?)
(s/def ::x2 number?)
(s/def ::y1 number?)
(s/def ::y2 number?)

(s/def ::attributes
  (s/keys :opt-un [::blocked
                   ::collapsed
                   ::content
                   ::fill-color
                   ::fill-opacity
                   ::font-family
                   ::font-size
                   ::font-style
                   ::font-weight
                   ::hidden
                   ::letter-spacing
                   ::line-height
                   ::locked
                   ::proportion
                   ::proportion-lock
                   ::rx ::ry
                   ::stroke-color
                   ::stroke-opacity
                   ::stroke-style
                   ::stroke-width
                   ::text-align
                   ::x1 ::x2
                   ::y1 ::y2]))

(s/def ::minimal-shape
  (s/keys :req-un [::id ::page ::type ::name]))

(s/def ::shape
  (s/and ::minimal-shape ::attributes))

(s/def ::rect-like-shape
  (s/keys :req-un [::x1 ::y1 ::x2 ::y2 ::type]))

(s/def ::set-of-uuid
  (s/every ::us/uuid :kind set?))

;; --- Expose inner functions

(defn interrupt? [e] (= e :interrupt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General workspace events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize Workspace

(declare initialize-alignment)

(def default-layout #{:sitemap :drawtools :layers :element-options :rules})

(def workspace-default
  {:zoom 1
   :flags #{:sitemap :drawtools :layers :element-options :rules}
   :selected #{}
   :drawing nil
   :drawing-tool nil
   :tooltip nil})

(declare initialized)
(declare watch-page-changes)
;; (declare watch-events)

(defn initialize
  "Initialize the workspace state."
  [file-id page-id]
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid page-id)
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [local (assoc workspace-default
                         :file-id file-id
                         :page-id page-id)]
        (-> state
            (assoc :workspace-layout default-layout)
            ;; (update :workspace-layout
            ;;         (fn [data]
            ;;           (if (nil? data) default-layout data)))
            (assoc :workspace-local local))))

    ptk/WatchEvent
    (watch [_ state stream]
      #_(when-not (get-in state [:pages page-id])
          (reset! st/loader true))

      (rx/merge
       ;; Stop possible previous watchers and re-fetch the main page
       ;; and all project related pages.
       (rx/of ::stop-watcher
              (udp/fetch-page page-id)
              (dp/fetch-file file-id)
              (udp/fetch-pages file-id))

       ;; When main page is fetched, schedule the main initialization.
       (->> (rx/zip (rx/filter (ptk/type? ::udp/page-fetched) stream)
                    (rx/filter (ptk/type? ::dp/files-fetched) stream))
            (rx/take 1)
            (rx/do #(reset! st/loader false))
            (rx/mapcat #(rx/of (initialized file-id page-id)
                               #_(initialize-alignment page-id))))

       ;; When workspace is initialized, run the event watchers.
       (->> (rx/filter (ptk/type? ::initialized) stream)
            (rx/take 1)
            (rx/mapcat #(rx/of watch-page-changes)))))
    ptk/EffectEvent
    (effect [_ state stream]
      ;; Optimistic prefetch of projects if them are not already fetched
      #_(when-not (seq (:projects state))
        (st/emit! (dp/fetch-projects))))))

(defn- initialized
  [file-id page-id]
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid page-id)
  (ptk/reify ::initialized
    ptk/UpdateEvent
    (update [_ state]
      (let [file (get-in state [:files file-id])
            page (get-in state [:pages page-id])
            data (get-in state [:pages-data page-id])]
        (assoc state
               :workspace-file file
               :workspace-data data
               :workspace-page page)))))

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

(defrecord IncreaseZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [increase #(nth c/zoom-levels
                         (+ (index-of c/zoom-levels %) 1)
                         (last c/zoom-levels))]
      (update-in state [:workspace :zoom] (fnil increase 1)))))

(defn increase-zoom
  []
  (IncreaseZoom.))

(defrecord DecreaseZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [decrease #(nth c/zoom-levels
                         (- (index-of c/zoom-levels %) 1)
                         (first c/zoom-levels))]
      (update-in state [:workspace :zoom] (fnil decrease 1)))))

(defn decrease-zoom
  []
  (DecreaseZoom.))

(defrecord ResetZoom []
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :zoom] 1)))

(defn reset-zoom
  []
  (ResetZoom.))

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

(defn add-shape
  [data]
  (ptk/reify ::add-shape
    udp/IPageDataUpdate
    ptk/UpdateEvent
    (update [_ state]
      ;; TODO: revisit the `setup-proportions` seems unnecesary
      (let [page-id (get-in state [:workspace-local :id])
            shape (assoc (geom/setup-proportions data)
                         :id (uuid/random))]
        (impl-assoc-shape state shape)))))

;; --- Duplicate Selected

(defn impl-duplicate-shape
  [state id]
  (let [shape (get-in state [:workspace-data :shapes-by-id id])]
    (assoc shape :id (uuid/random))))

(def duplicate-selected
  (ptk/reify ::duplicate-selected
    udp/IPageDataUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [selected  (get-in state [:workspace-local :selected])
            duplicate (partial impl-duplicate-shape state)
            shapes    (map duplicate selected)]
        (reduce impl-assoc-shape state shapes)))))

;; --- Delete shape to Workspace

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

(defn delete-shape
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::delete-shape
    udp/IPageDataUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:workspace-data :shapes-by-id id])]
        (impl-dissoc-shape state shape)))))

(defn delete-many-shapes
  [ids]
  (s/assert ::us/set ids)
  (ptk/reify ::delete-many-shapes
    udp/IPageDataUpdate
    ptk/UpdateEvent
    (update [_ state]
      (reduce impl-dissoc-shape state
              (map #(get-in state [:workspace-data :shapes-by-id %]) ids)))))


;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  "Mark a shape selected for drawing."
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

;; --- Select First Shape

;; TODO: first???

(def select-first-shape
  (ptk/reify ::select-first-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace-local :id])
            sid (first (get-in state [:workspace-data :shapes]))]
        (assoc-in state [:workspace-local :selected] #{sid})))))

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

(defn update-shape-attrs
  [id attrs]
  (s/assert ::us/uuid id)
  (s/assert ::attributes attrs)
  (let [atts (s/conform ::attributes attrs)]
    (ptk/reify ::update-shape-attrs
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:workspace-data :shapes-by-id id] merge attrs)))))

;; --- Update Selected Shapes attrs

;; TODO: improve performance of this event

(defn update-selected-shapes-attrs
  [attrs]
  (s/assert ::attributes attrs)
  (ptk/reify ::update-selected-shapes-attrs
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/from-coll (map #(update-shape-attrs % attrs) selected))))))

;; --- Move Selected

;; Event used for apply displacement transformation
;; to the selected shapes throught the keyboard shortcuts.

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [direction speed distance]
  (case direction
    :up (gpt/point 0 (- (get-in distance [speed :y])))
    :down (gpt/point 0 (get-in distance [speed :y]))
    :left (gpt/point (- (get-in distance [speed :x])) 0)
    :right (gpt/point (get-in distance [speed :x]) 0)))

(defn- get-displacement-distance
  "Retrieve displacement distances thresholds for
  defined displacement speeds."
  [metadata align?]
  (let [gx (:grid-x-axis metadata)
        gy (:grid-y-axis metadata)]
    {:std (gpt/point (if align? gx 1)
                     (if align? gy 1))
     :fast (gpt/point (if align? (* 3 gx) 10)
                      (if align? (* 3 gy) 10))}))

(declare initial-selection-align)
(declare materialize-current-modifier-in-bulk)
(declare apply-temporal-displacement-in-bulk)

(s/def ::direction #{:up :down :right :left})
(s/def ::speed #{:std :fast})

(defn move-selected
  [direction speed]
  (s/assert ::direction direction)
  (s/assert ::speed speed)
  (ptk/reify ::move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [selected flags id]} (:workspace-local state)
            align? (refs/alignment-activated? flags)
            metadata (merge c/page-metadata
                            (get-in state [:workspace-page :metadata]))
            distance (get-displacement-distance metadata align?)
            displacement (get-displacement direction speed distance)]
        (rx/concat
         (when align? (rx/of (initial-selection-align selected)))
         (rx/of (apply-temporal-displacement-in-bulk selected displacement))
         (rx/of (materialize-current-modifier-in-bulk selected)))))))

;; --- Update Shape Position

(deftype UpdateShapePosition [id point]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes id] geom/absolute-move point)))

(defn update-position
  "Update the start position coordenate of the shape."
  [id point]
  {:pre [(uuid? id) (gpt/point? point)]}
  (UpdateShapePosition. id point))

;; --- Delete Selected

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/of (delete-many-shapes selected))))))

;; --- Rename Shape

(defn rename-shape
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-shape
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes id :name] name))))

;; --- Change Shape Order (D&D Ordering)

(defn change-shape-order
  [{:keys [id index] :as params}]
  {:pre [(uuid? id) (number? index)]}
  (ptk/reify ::change-shape-order
    ptk/UpdateEvent
    (update [_ state]
      (let [shapes (get-in state [:workspace-data :shapes])
            shapes (into [] (remove #(= % id)) shapes)
            [before after] (split-at index shapes)
            shapes (vec (concat before [id] after))]
        (assoc-in state [:workspace-data :shapes] shapes)))))

;; --- Shape Vertical Ordering

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

(defn order-selected-shapes
  [loc]
  (s/assert ::direction loc)
  (ptk/reify ::move-selected-layer
    udp/IPageDataUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [id (first (get-in state [:workspace-local :selected]))
            type (get-in state [:workspace-data :shapes-by-id id :type])]
        ;; NOTE: multiple selection ordering not supported
        (if (and id (not= type :canvas))
          (impl-order-shape state id loc)
          state)))))

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
      ;; udp/IPageOps
      ;; (-ops [_]
      ;;   (mapv #(vec :udp/shape id :move delta) ids))

      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids)))))

;; --- Modifiers

(defn assoc-temporal-modifier-in-bulk
  [ids xfmt]
  (s/assert ::set-of-uuid ids)
  (s/assert gmt/matrix? xfmt)
  (ptk/reify ::assoc-temporal-modifier-in-bulk
    ptk/UpdateEvent
    (update [_ state]
      (reduce #(assoc-in %1 [:workspace-data :shapes-by-id %2 :modifier-mtx] xfmt) state ids))))

(defn materialize-current-modifier-in-bulk
  [ids]
  (s/assert ::us/set ids)
  (letfn [(process-shape [state id]
            (let [xfmt (get-in state [:workspace-data :shapes-by-id id :modifier-mtx])]
              (if (gmt/matrix? xfmt)
                (-> state
                    (update-in [:workspace-data :shapes-by-id id] geom/transform xfmt)
                    (update-in [:workspace-data :shapes-by-id id] dissoc :modifier-mtx))
                state)))]
    (ptk/reify ::materialize-current-modifier-in-bulk
      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids)))))

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
       (update state :workspace-local assoc :drawing-tool tool :drawing data)))))

;; --- Shape Proportions

;; TODO: revisit

(deftype LockShapeProportions [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [[width height] (-> (get-in state [:shapes id])
                             (geom/size)
                             (keep [:width :height]))
          proportion (/ width height)]
      (update-in state [:shapes id] assoc
                 :proportion proportion
                 :proportion-lock true))))

(defn lock-proportions
  "Mark proportions of the shape locked and save the current
  proportion as additional precalculated property."
  [id]
  {:pre [(uuid? id)]}
  (LockShapeProportions. id))

;; TODO: revisit

(deftype UnlockShapeProportions [id]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:shapes id :proportion-lock] false)))

(defn unlock-proportions
  [id]
  {:pre [(uuid? id)]}
  (UnlockShapeProportions. id))

;; --- Update Dimensions

;; TODO: revisit

(s/def ::width (s/and ::us/number ::us/positive))
(s/def ::height (s/and ::us/number ::us/positive))

(s/def ::update-dimensions
  (s/keys :opt-un [::width ::height]))

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
      (update-in state [:shapes id] geom/resize-dim dimensions))))

;; --- Update Interaction

;; TODO: revisit
(deftype UpdateInteraction [shape interaction]
  ptk/UpdateEvent
  (update [_ state]
    (let [id (or (:id interaction)
                 (uuid/random))
          data (assoc interaction :id id)]
      (assoc-in state [:shapes shape :interactions id] data))))

(defn update-interaction
  [shape interaction]
  (UpdateInteraction. shape interaction))

;; --- Delete Interaction

;; TODO: revisit
(deftype DeleteInteracton [shape id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes shape :interactions] dissoc id)))

(defn delete-interaction
  [shape id]
  {:pre [(uuid? id) (uuid? shape)]}
  (DeleteInteracton. shape id))

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

;; --- Recalculate Shapes relations (Shapes <-> Canvas)

(def rehash-shapes-relationships
  (letfn [(overlaps? [canvas shape]
            (let [shape1 (geom/shape->rect-shape canvas)
                  shape2 (geom/shape->rect-shape shape)]
              (geom/overlaps? shape1 shape2)))]
    (ptk/reify ::rehash-shapes-relationships
      ptk/UpdateEvent
      (update [_ state]
        (let [data (:workspace-data state)
              canvas (map #(get-in data [:shapes-by-id %]) (:canvas data))
              shapes (map #(get-in data [:shapes-by-id %]) (:shapes data))]
          (reduce (fn [state {:keys [id] :as shape}]
                    (let [canvas (first (filter #(overlaps? % shape) canvas))]
                      (update-in state [:workspace-data :shapes-by-id id] assoc :canvas (:id canvas))))
                  state
                  shapes))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Selection Rect IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: move to shapes impl maybe...

(defn selection->rect
  [data]
  (let [start (:start data)
        stop (:stop data)
        start-x (min (:x start) (:x stop))
        start-y (min (:y start) (:y stop))
        end-x (max (:x start) (:x stop))
        end-y (max (:y start) (:y stop))]
    (assoc data
           :x1 start-x
           :y1 start-y
           :x2 end-x
           :y2 end-y
           :type :rect)))

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
;; Server Interactions DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Update Metadata

;; Is a workspace aware wrapper over uxbox.data.pages/UpdateMetadata event.

(defn update-metadata
  [id metadata]
  (s/assert ::us/uuid id)
  (s/assert ::udp/metadata metadata)
  (ptk/reify ::update-metadata
    ptk/WatchEvent
    (watch [_ state s]
      #_(rx/of (udp/update-metadata id metadata)
             (initialize-alignment id)))))

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

;; -- Page Changes Watcher

(def watch-page-changes
  (ptk/reify ::watch-page-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/filter #(= % ::stop-watcher) stream)]
        (->> stream
             (rx/filter udp/page-update?)
             (rx/debounce 500)
             (rx/mapcat #(rx/of rehash-shapes-relationships
                                udp/persist-current-page))
             (rx/take-until stopper))))))

;; (def watch-shapes-changes
;;   (letfn [(look-for-changes [[old new]]
;;             (reduce-kv (fn [acc k v]
;;                          (if (identical? v (get old k))
;;                            acc
;;                            (conj acc k)))
;;                        #{}
;;                        new))
;;           (select-shapes [state]
;;             (get-in state [:workspace-data :shapes-by-id]))
;;           ]
;;     (ptk/reify ::watch-page-changes
;;       ptk/WatchEvent
;;       (watch [_ state stream]
;;         (let [stopper (rx/filter #(= % ::stop-page-watcher) stream)]
;;           (->> stream
;;                (rx/filter udp/page-update?)
;;                (rx/debounce 1000)
;;                (rx/mapcat #(rx/merge (rx/of persist-page
;;                                    (->> (rx/filter page-persisted? stream)
;;                                         (rx/timeout 1000 (rx/empty))
;;                                         (rx/take 1)
;;                                         (rx/ignore)))))
;;              (rx/take-until stopper))))))



;;         (let [stoper (rx/filter #(= % ::stop-shapes-watcher) stream)
;;               into' (fn [dst srcs] (reduce #(into %1 %2) dst srcs))]
;;           (->> (rx/merge st/store (rx/of state))
;;                (rx/map #(get-in % [:workspace-data :shapes-by-id]))
;;                (rx/buffer 2 1)
;;                (rx/map look-for-changes)
;;                (rx/buffer-time 300)
;;                (rx/map #(into' #{} %))
;;                (rx/filter (complement empty?))
;;                ;; (rx/tap #(prn "changed" %))
;;                ;; (rx/mapcat (fn [items] (rx/from-coll
;;                ;;                         (map rehash-shape-relationship items))))
;;                (rx/ignore)
;;                (rx/take-until stoper)))))))

