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
   [uxbox.main.data.shapes :as ds]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.data :refer [dissoc-in index-of]]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.spec :as us]
   [uxbox.util.perf :as perf]
   [uxbox.util.time :as dt]
   [uxbox.util.uuid :as uuid]))

(s/def ::set-of-uuid
  (s/every ::us/uuid :kind set?))

;; --- Expose inner functions

(def start-ruler nil)
(def clear-ruler nil)

(defn interrupt? [e] (= e :interrupt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General workspace events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize Workspace

(declare initialize-alignment)

(def workspace-default-data
  {:initialized true
   :zoom 1
   :flags #{:sitemap :drawtools :layers :element-options :rules}
   :selected #{}
   :drawing nil
   :drawing-tool nil
   :tooltip nil})

(defn initialize
  "Initialize the workspace state."
  [project-id page-id]
  (s/assert ::us/uuid project-id)
  (s/assert ::us/uuid page-id)
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [data (assoc workspace-default-data
                        :project-id project-id
                        :page-id page-id)]
        (-> state
            (update-in [:workspace page-id]
                       (fn [wsp]
                         (if (:initialized wsp)
                           wsp
                           (merge wsp data))))
            (assoc-in [:workspace :current] page-id))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page (get-in state [:pages page-id])]
        ;; Activate loaded if page is not fetched.
        (when-not page (reset! st/loader true))
        (rx/merge
         ;; TODO: the `fetch-pages` should fetch a limited set of attrs?
         (rx/of (udp/fetch-page page-id))
         (rx/of (udp/fetch-pages project-id))
         (->> stream
              (rx/filter udp/page-fetched?)
              (rx/take 1)
              (rx/mapcat (fn [event]
                           (reset! st/loader false)
                           (rx/of (initialize-alignment page-id))))))))
    ptk/EffectEvent
    (effect [_ state stream]
      ;; Optimistic prefetch of projects if them are not already fetched
      (when-not (seq (:projects state))
        (st/emit! (dp/fetch-projects))))))

;; --- Workspace Tooltips

(defrecord SetTooltip [text]
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :current])]
      (assoc-in state [:workspace page-id :tooltip] text))))

(defn set-tooltip
  [text]
  (SetTooltip. text))

;; --- Workspace Flags

(defrecord ActivateFlag [flag]
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :current])]
      (update-in state [:workspace page-id :flags]
                 (fn [flags]
                   (if (contains? flags flag)
                     flags
                     (conj flags flag)))))))

(defn activate-flag
  [flag]
  {:pre [(keyword? flag)]}
  (ActivateFlag. flag))

(defrecord DeactivateFlag [flag]
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :current])]
      (update-in state [:workspace page-id :flags] disj flag))))

(defn deactivate-flag
  [flag]
  {:pre [(keyword? flag)]}
  (DeactivateFlag. flag))

(defrecord ToggleFlag [flag]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page-id (get-in state [:workspace :current])
          flags (get-in state [:workspace page-id :flags])]
      (if (contains? flags flag)
        (rx/of (deactivate-flag flag))
        (rx/of (activate-flag flag))))))

(defn toggle-flag
  [flag]
  (ToggleFlag. flag))

;; --- Workspace Ruler

(defrecord ActivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (set-tooltip "Drag to use the ruler")
           (activate-flag :ruler))))

(defn activate-ruler
  []
  (ActivateRuler.))

(defrecord DeactivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (set-tooltip nil)
           (deactivate-flag :ruler))))

(defn deactivate-ruler
  []
  (DeactivateRuler.))

(defrecord ToggleRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page-id (get-in state [:workspace :current])
          flags (get-in state [:workspace page-id :flags])]
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
    (let [page-id (get-in state [:workspace :current])]
      (assoc-in state [:workspace page-id :icons-toolbox] id)))

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
    (let [page-id (get-in state [:workspace :current])
          selected (get-in state [:workspace page-id :selected])
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
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :current])
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
                         (last c/zoom-levels))
          page-id (get-in state [:workspace :current])]
      (update-in state [:workspace page-id :zoom] (fnil increase 1)))))

(defn increase-zoom
  []
  (IncreaseZoom.))

(defrecord DecreaseZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [decrease #(nth c/zoom-levels
                         (- (index-of c/zoom-levels %) 1)
                         (first c/zoom-levels))
          page-id (get-in state [:workspace :current])]
      (update-in state [:workspace page-id :zoom] (fnil decrease 1)))))

(defn decrease-zoom
  []
  (DecreaseZoom.))

(defrecord ResetZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :current])]
      (assoc-in state [:workspace page-id :zoom] 1))))

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
      (let [metadata (get-in state [:pages id :metadata])
            params {:width c/viewport-width
                    :height c/viewport-height
                    :x-axis (:grid-x-axis metadata c/grid-x-axis)
                    :y-axis (:grid-y-axis metadata c/grid-y-axis)}]
        (rx/concat
         (rx/of (deactivate-flag :grid-indexed))
         (->> (uwrk/initialize-alignment params)
              (rx/map #(activate-flag :grid-indexed))))))))

;; --- Duplicate Selected

(def duplicate-selected
  (ptk/reify ::duplicate-selected
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            selected (get-in state [:workspace pid :selected])]
        (ds/duplicate-shapes state selected)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: add spec

(defn add-shape
  [data]
  (ptk/reify ::add-shape
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      ;; TODO: revisit the `setup-proportions` seems unnecesary
      (let [shape (assoc (geom/setup-proportions data)
                         :id (uuid/random))
            pid (get-in state [:workspace :current])]
        (ds/assoc-shape-to-page state shape pid)))))

(defn delete-shape
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::delete-shape
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:shapes id])]
        (ds/dissoc-shape state shape)))))

(defn delete-many-shapes
  [ids]
  (s/assert ::us/set ids)
  (ptk/reify ::delete-many-shapes
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (reduce ds/dissoc-shape state
              (map #(get-in state [:shapes %]) ids)))))

(defn select-shape
  "Mark a shape selected for drawing."
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::select-shape
    ptk/UpdateEvent
    (update [_ state]
      (prn "select-shape$update" id)
      (let [pid (get-in state [:workspace :current])
            selected (get-in state [:workspace pid :selected])]
        (update-in state [:workspace pid :selected]
                   (fn [selected]
                     (if (contains? selected id)
                       (disj selected id)
                       (conj selected id))))))

    ptk/WatchEvent
    (watch [_ state s]
      (prn "select-shape$watch" id)
      (rx/of (activate-flag :element-options)))))

(def deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (prn "deselect-all")
      (let [pid (get-in state [:workspace :current])]
        (update-in state [:workspace pid] #(-> %
                                               (assoc :selected #{})
                                               (dissoc :selected-canvas)))))))

;; --- Select First Shape

(def select-first-shape
  (ptk/reify ::select-first-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            sid (first (get-in state [:pages pid :shapes]))]
        (assoc-in state [:workspace pid :selected] #{sid})))))

;; --- Select Shapes (By selrect)

(def select-shapes-by-current-selrect
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            selrect (get-in state [:workspace pid :selrect])
            shapes (ds/match-by-selrect state pid selrect)]
        (assoc-in state [:workspace pid :selected] shapes)))))

;; --- Update Shape Attrs

(defn update-shape-attrs
  [id attrs]
  (s/assert ::us/uuid id)
  (s/assert ::ds/attributes attrs)
  (let [atts (s/conform ::ds/attributes attrs)]
    (ptk/reify ::update-shape-attrs
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:shapes id] merge attrs)))))

;; --- Update Selected Shapes attrs

;; TODO: improve performance of this event

(defn update-selected-shapes-attrs
  [attrs]
  (s/assert ::ds/attributes attrs)
  (ptk/reify ::update-selected-shapes-attrs
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            selected (get-in state [:workspace pid :selected])]
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
      (let [page-id (get-in state [:workspace :current])
            workspace (get-in state [:workspace page-id])
            selected (:selected workspace)
            flags (:flags workspace)
            align? (refs/alignment-activated? flags)
            metadata (merge c/page-metadata (get-in state [:pages page-id :metadata]))
            distance (get-displacement-distance metadata align?)
            displacement (get-displacement direction speed distance)]
        (rx/concat
         (when align?
           (rx/of (initial-selection-align selected)))
         (rx/of (apply-temporal-displacement-in-bulk selected displacement))
         (rx/of (materialize-current-modifier-in-bulk selected)))))))

;; --- Move Selected Layer

(defn order-selected-shapes
  [loc]
  (s/assert ::direction loc)
  (ptk/reify ::move-selected-layer
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace :current])
            selected (get-in state [:workspace id :selected])]
        ;; NOTE: multiple selection ordering not supported
        (if (pos? (count selected))
          (ds/order-shape state (first selected) loc)
          state)))))

;; --- Update Shape Position

(deftype UpdateShapePosition [id point]
  udp/IPageUpdate
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
      (let [id (get-in state [:workspace :current])
            selected (get-in state [:workspace id :selected])]
        (rx/of (delete-many-shapes selected))))))

;; --- Rename Shape

(defn rename-shape
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-shape
    udp/IPageUpdate
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
      (let [page-id (get-in state [:shapes id :page])
            shapes (get-in state [:pages page-id :shapes])
            shapes (into [] (remove #(= % id)) shapes)
            [before after] (split-at index shapes)
            shapes (vec (concat before [id] after))]
        (assoc-in state [:pages page-id :shapes] shapes)))))

;; --- Change Canvas Order (D&D Ordering)

(defn change-canvas-order
  [{:keys [id index] :as params}]
  (s/assert ::us/uuid id)
  (s/assert ::us/number index)
  (ptk/reify ::change-canvas-order
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get-in state [:shapes id :page])
            canvas (get-in state [:pages page-id :canvas])
            canvas (into [] (remove #(= % id)) canvas)
            [before after] (split-at index canvas)
            canvas (vec (concat before [id] after))]
        (assoc-in state [:pages page-id :canvas] canvas)))))

;; --- Shape / Selection Alignment

(defn initial-selection-align
  "Align the selection of shapes."
  [ids]
  (s/assert ::set-of-uuid ids)
  (ptk/reify ::initialize-shapes-align-in-bulk
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shapes-by-id (:shapes state)
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
            (let [prev (get-in state [:shapes id :modifier-mtx] (gmt/matrix))
                  xfmt (gmt/translate prev delta)]
              (assoc-in state [:shapes id :modifier-mtx] xfmt)))]
    (ptk/reify ::apply-temporal-displacement-in-bulk
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
      (reduce #(assoc-in %1 [:shapes %2 :modifier-mtx] xfmt) state ids))))

(defn materialize-current-modifier-in-bulk
  [ids]
  (s/assert ::us/set ids)
  (letfn [(process-shape [state id]
            (let [xfmt (get-in state [:shapes id :modifier-mtx])]
              (if (gmt/matrix? xfmt)
                (-> state
                    (update-in [:shapes id] geom/transform xfmt)
                    (update-in [:shapes id] dissoc :modifier-mtx))
                state)))]
    (ptk/reify ::materialize-current-modifier-in-bulk
      udp/IPageUpdate
      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids)))))

(defn rehash-shape-relationship
  "Checks shape overlaping with existing canvas, if one or more
  overlaps, assigns the shape to the first one."
  [id]
  (s/assert ::us/uuid id)
  (letfn [(overlaps? [canvas shape]
            (let [shape1 (geom/shape->rect-shape canvas)
                  shape2 (geom/shape->rect-shape shape)]
              (geom/overlaps? shape1 shape2)))]
    (ptk/reify ::rehash-shape-relationship
      ptk/UpdateEvent
      (update [_ state]
        (let [shape (get-in state [:shapes id])
              xform (comp (map #(get-in state [:shapes %]))
                          (filter #(overlaps? % shape))
                          (take 1))
              canvas (->> (get-in state [:pages (:page shape) :canvas])
                          (sequence xform)
                          (first))]
          (if canvas
            (update-in state [:shapes id] assoc :canvas (:id canvas))
            (update-in state [:shapes id] assoc :canvas nil)))))))

;; --- Start shape "edition mode"

(defn start-edition-mode
  [id]
  {:pre [(uuid? id)]}
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])]
        (assoc-in state [:workspace pid :edition] id)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])]
        (->> stream
             (rx/filter #(= % :interrupt))
             (rx/take 1)
             (rx/map (fn [_] #(dissoc-in % [:workspace pid :edition]))))))))

;; --- Select for Drawing

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])]
        (update-in state [:workspace pid] dissoc :drawing-tool :drawing)))))

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (ptk/reify ::select-for-drawing
     ptk/UpdateEvent
     (update [_ state]
       (let [pid (get-in state [:workspace :current])]
         (update-in state [:workspace pid] assoc :drawing-tool tool :drawing data))))))

;; --- Shape Proportions

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

(deftype UnlockShapeProportions [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:shapes id :proportion-lock] false)))

(defn unlock-proportions
  [id]
  {:pre [(uuid? id)]}
  (UnlockShapeProportions. id))

;; --- Update Dimensions

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
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes id] geom/resize-dim dimensions))))

;; --- Update Interaction

(deftype UpdateInteraction [shape interaction]
  udp/IPageUpdate
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

(deftype DeleteInteracton [shape id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes shape :interactions] dissoc id)))

(defn delete-interaction
  [shape id]
  {:pre [(uuid? id) (uuid? shape)]}
  (DeleteInteracton. shape id))

;; --- Path Modifications

(deftype UpdatePath [id index delta]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes id :segments index] gpt/add delta)))

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  {:pre [(uuid? id) (number? index) (gpt/point? delta)]}
  (UpdatePath. id index delta))

;; --- Initial Path Point Alignment

(deftype InitialPathPointAlign [id index]
  ptk/WatchEvent
  (watch [_ state s]
    (let [shape (get-in state [:shapes id])
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
      udp/IPageUpdate
      ptk/UpdateEvent
      (update [_ state]
        (impl-set-hidden state id)))))

;; --- Shape Blocking

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
      udp/IPageUpdate
      ptk/UpdateEvent
      (update [_ state]
        (impl-set-blocked state id)))))

;; --- Shape Locking

(deftype LockShape [id]
  udp/IPageUpdate
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

(defn lock-shape
  [id]
  {:pre [(uuid? id)]}
  (LockShape. id))

(deftype UnlockShape [id]
  udp/IPageUpdate
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

(defn unlock-shape
  [id]
  {:pre [(uuid? id)]}
  (UnlockShape. id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pages
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn delete-page
  [id]
  {:pre [(uuid? id)]}
  (ptk/reify ::delete-page
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:pages id :project])]
        (rx/merge
         (rx/of (udp/delete-page id))
         (->> stream
              (rx/filter #(= % ::udp/delete-completed))
              (rx/map #(dp/go-to pid))
              (rx/take 1)))))))


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
      (let [pid (get-in state [:workspace :current])]
        (update-in state [:workspace pid] assoc :selected-canvas id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server Interactions
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
      (rx/of (udp/update-metadata id metadata)
             (initialize-alignment id)))))

(defrecord OpenView [page-id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [page-id (get-in state [:workspace :page])]
      (rx/of (udp/persist-page page-id))))

  ptk/EffectEvent
  (effect [_ state s]
    (let [rval (rand-int 1000000)
          page (get-in state [:pages page-id])
          project (get-in state [:projects (:project page)])
          url (str cfg/viewurl "?v=" rval "#/preview/" (:share-token project) "/" page-id)]
      (js/open url "new tab" ""))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Changes Reactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-shapes-watcher
  [id]
  (s/assert ::us/uuid id)
  (letfn [(on-change [[old new]]
            (reduce-kv (fn [acc k v]
                         (if (identical? v (get old k))
                           acc
                           (conj acc k)))
                       #{}
                       new))
          (select-shapes [state]
            (let [ids (get-in state [:pages id :shapes])]
              (select-keys (:shapes state) ids)))
          ]
    (ptk/reify ::watch-page-changes
      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper (rx/filter #(= % ::stop-shapes-watcher) stream)
              ids (get-in state [:pages id :shapes])
              local (volatile! nil)
              into* (fn [dst srcs] (reduce #(into %1 %2) dst srcs))]
          (->> (rx/merge st/store (rx/of state))
               (rx/map select-shapes)
               (rx/buffer 2 1)
               (rx/map on-change)
               (rx/buffer-time 300)
               (rx/map #(into* #{} %))
               (rx/filter (complement empty?))
               ;; (rx/tap #(prn "changed" %))
               (rx/mapcat (fn [items] (rx/from-coll
                                       (map rehash-shape-relationship items))))
               (rx/take-until stoper)))))))

