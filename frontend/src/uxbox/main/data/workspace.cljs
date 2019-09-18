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
   [uxbox.main.data.history :as udh]
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
   [uxbox.util.time :as dt]
   [uxbox.util.uuid :as uuid]))

;; --- Expose inner functions

(def start-ruler nil)
(def clear-ruler nil)

(defn interrupt? [e] (= e :interrupt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General workspace events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize Workspace

(declare initialize-alignment)

(defrecord Initialize [project-id page-id]
  ptk/UpdateEvent
  (update [_ state]
    (let [default-flags #{:sitemap :drawtools :layers :element-options :rules}
          initial-workspace {:project-id project-id
                             :page-id page-id
                             :zoom 1
                             :flags default-flags
                             :selected #{}
                             :drawing nil
                             :drawing-tool nil
                             :tooltip nil}]
      (-> state
          (update-in [:workspace page-id] #(if (nil? %) initial-workspace %))
          (assoc-in [:workspace :current] page-id))))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [page (get-in state [:pages page-id])]

      ;; Activate loaded if page is not fetched.
      (when-not page (reset! st/loader true))

      (if page
        (rx/of (initialize-alignment page-id))
        (rx/merge
         (rx/of (udp/fetch-pages project-id))
         (->> stream
              (rx/filter udp/pages-fetched?)
              (rx/take 1)
              (rx/do #(reset! st/loader false))
              (rx/map #(initialize-alignment page-id)))))))

  ptk/EffectEvent
  (effect [_ state stream]
    ;; Optimistic prefetch of projects if them are not already fetched
    (when-not (seq (:projects state))
      (st/emit! (dp/fetch-projects)))))

(defn initialize
  "Initialize the workspace state."
  [project page]
  {:pre [(uuid? project)
         (uuid? page)]}
  (Initialize. project page))

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

(defrecord InitializeAlignment [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [{:keys [metadata] :as page} (get-in state [:pages id])
          params {:width c/viewport-width
                  :height c/viewport-height
                  :x-axis (:grid-x-axis metadata c/grid-x-axis)
                  :y-axis (:grid-y-axis metadata c/grid-y-axis)}]
      (rx/concat
       (rx/of (deactivate-flag :grid-indexed))
       (->> (uwrk/initialize-alignment params)
            (rx/map #(activate-flag :grid-indexed)))))))

(defn initialize-alignment?
  [v]
  (instance? InitializeAlignment v))

(defn initialize-alignment
  [id]
  {:pre [(uuid? id)]}
  (InitializeAlignment. id))

;; --- Duplicate Selected

(def duplicate-selected
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (ds/duplicate-shapes state selected)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-shape
  [data]
  (reify
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
  {:pre [(uuid? id)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:shapes id])]
        (ds/dissoc-shape state shape)))))

(defrecord SelectShape [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [pid (get-in state [:workspace :current])
          selected (get-in state [:workspace pid :selected])]
      (if (contains? selected id)
        (update-in state [:workspace pid :selected] disj id)
        (update-in state [:workspace pid :selected] conj id))))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (activate-flag :element-options))))

(defn select-shape
  "Mark a shape selected for drawing."
  [id]
  {:pre [(uuid? id)]}
  (SelectShape. id))

(defrecord DeselectAll []
  ptk/UpdateEvent
  (update [_ state]
    (let [pid (get-in state [:workspace :current])]
      (update-in state [:workspace pid] #(-> %
                                             (assoc :selected #{})
                                             (dissoc :selected-canvas)))))
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/just :interrupt)))

(defn deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  []
  (DeselectAll.))

;; --- Select First Shape

(deftype SelectFirstShape []
  ptk/UpdateEvent
  (update [_ state]
    (let [pid (get-in state [:workspace :current])
          sid (first (get-in state [:pages pid :shapes]))]
      (assoc-in state [:workspace pid :selected] #{sid}))))

(defn select-first-shape
  "Mark a shape selected for drawing."
  []
  (SelectFirstShape.))

;; --- Select Shapes (By selrect)

(def select-shapes-by-current-selrect
  (reify
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
    (reify
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:shapes id] merge attrs)))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes-attrs
  [attrs]
  (s/assert ::ds/attributes attrs)
  (reify
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

(declare initial-shape-align)
(declare apply-displacement)
(declare assoc-temporal-modifier)
(declare materialize-current-modifier)
(declare apply-temporal-displacement)

(s/def ::direction #{:up :down :right :left})
(s/def ::speed #{:std :fast})

(defn move-selected
  [direction speed]
  (s/assert ::direction direction)
  (s/assert ::speed speed)
  (reify
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
           (rx/concat
            (rx/from-coll (map initial-shape-align selected))
            (rx/from-coll (map apply-displacement selected))))
         (rx/from-coll (map #(apply-temporal-displacement % displacement) selected))
         (rx/from-coll (map materialize-current-modifier selected)))))))

;; --- Move Selected Layer

(defn move-selected-layer
  [loc]
  (assert (s/valid? ::direction loc))
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace :current])
            selected (get-in state [:workspace id :selected])]
        (ds/move-layer state selected loc)))))

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
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace :current])
            selected (get-in state [:workspace id :selected])]
        (rx/from-coll
         (into [(deselect-all)] (map #(delete-shape %) selected)))))))

;; --- Rename Shape

(defn rename-shape
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes id :name] name))))

;; --- Change Shape Order (D&D Ordering)

(defn change-shape-order
  [{:keys [id index] :as params}]
  {:pre [(uuid? id) (number? index)]}
  (reify
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
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get-in state [:shapes id :page])
            canvas (get-in state [:pages page-id :canvas])
            canvas (into [] (remove #(= % id)) canvas)
            [before after] (split-at index canvas)
            canvas (vec (concat before [id] after))]
        (assoc-in state [:pages page-id :canvas] canvas)))))

;; --- Shape Transformations

(defn initial-shape-align
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (let [{:keys [x1 y1] :as shape} (->> (get-in state [:shapes id])
                                           (geom/shape->rect-shape state))
            point (gpt/point x1 y1)]
        (->> (uwrk/align-point point)
             (rx/map (fn [{:keys [x y] :as pt}]
                       (apply-temporal-displacement id (gpt/subtract pt point)))))))))


;; --- Apply Temporal Displacement

(defn apply-temporal-displacement
  [id delta]
  {:pre [(uuid? id) (gpt/point? delta)]}
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:shapes id :modifier-mtx] (gmt/matrix))
            curr (gmt/translate prev delta)]
        (rx/of (assoc-temporal-modifier id curr))))))

;; --- Modifiers

(defn assoc-temporal-modifier
  [id xfmt]
  {:pre [(uuid? id)
         (gmt/matrix? xfmt)]}
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes id :modifier-mtx] xfmt))))

(defn materialize-current-modifier
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [xfmt (get-in state [:shapes id :modifier-mtx])]
        (when (gmt/matrix? xfmt)
          (rx/of #(update-in % [:shapes id] geom/transform xfmt)
                 #(update-in % [:shapes id] dissoc :modifier-mtx)
                 ::udp/page-update))))))

(defn rehash-shape-relationship
  "Checks shape overlaping with existing canvas, if one or more
  overlaps, assigns the shape to the first one."
  [id]
  (s/assert ::us/uuid id)
  (letfn [(overlaps? [canvas shape]
            (let [shape1 (geom/shape->rect-shape canvas)
                  shape2 (geom/shape->rect-shape shape)]
              (geom/overlaps? shape1 shape2)))]
    (reify
      ptk/EventType
      (type [_] ::rehash-shape-relationship)

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
  (reify
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
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])]
        (update-in state [:workspace pid] dissoc :drawing-tool :drawing)))))

(defn select-for-drawing?
  [e]
  (= (::type (meta e)) ::select-for-drawing))

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (reify
     IMeta
     (-meta [_] {::type ::select-for-drawing})

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
  (reify
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
    (reify
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
    (reify
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
  (reify
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

;; ;; --- Group Collapsing

;; (deftype CollapseGroupShape [id]
;;   udp/IPageUpdate
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (update-in state [:shapes id] assoc :collapsed true)))

;; (defn collapse-shape
;;   [id]
;;   {:pre [(uuid? id)]}
;;   (CollapseGroupShape. id))

;; (deftype UncollapseGroupShape [id]
;;   udp/IPageUpdate
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (update-in state [:shapes id] assoc :collapsed false)))

;; (defn uncollapse-shape
;;   [id]
;;   {:pre [(uuid? id)]}
;;   (UncollapseGroupShape. id))

(defn select-canvas
  [id]
  (reify
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
  (reify
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
