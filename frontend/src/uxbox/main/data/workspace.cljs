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
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.shapes-impl :as simpl]
   [uxbox.main.data.workspace.ruler :as wruler]
   [uxbox.main.geom :as geom]
   [uxbox.main.lenses :as ul]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.data :refer [dissoc-in index-of]]
   [uxbox.util.forms :as sc]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.spec :as us]
   [uxbox.util.time :as dt]
   [uxbox.util.uuid :as uuid]))

;; --- Expose inner functions

(def start-ruler wruler/start-ruler)
(def clear-ruler wruler/clear-ruler)

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
      (simpl/duplicate-shapes state (:items selected) page-id))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes on Workspace events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord SelectShape [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :current])
          selected (get-in state [:workspace page-id :selected])]
      (if (contains? selected id)
        (update-in state [:workspace page-id :selected] disj id)
        (update-in state [:workspace page-id :selected] conj id))))

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
    (let [page-id (get-in state [:workspace :current])]
      (assoc-in state [:workspace page-id :selected] #{})))

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
            shapes (simpl/match-by-selrect state pid selrect)]
        (assoc-in state [:workspace pid :selected] shapes)))))

;; --- Update Shape Attrs

(deftype UpdateShapeAttrs [id attrs]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes id] merge attrs)))

(defn update-shape-attrs
  [id attrs]
  {:pre [(uuid? id) (us/valid? ::uds/attributes attrs)]}
  (let [atts (us/extract attrs ::uds/attributes)]
    (UpdateShapeAttrs. id attrs)))

;; --- Update Selected Shapes attrs


(deftype UpdateSelectedShapesAttrs [attrs]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [pid (get-in state [:workspace :current])
          selected (get-in state [:workspace pid :selected])]
      (rx/from-coll (map #(update-shape-attrs % attrs) selected)))))

(defn update-selected-shapes-attrs
  [attrs]
  {:pre [(us/valid? ::uds/attributes attrs)]}
  (UpdateSelectedShapesAttrs. attrs))


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

(declare apply-temporal-displacement)
(declare initial-shape-align)
(declare apply-displacement)

(defrecord MoveSelected [direction speed]
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
       (rx/from-coll (map apply-displacement selected))))))

(s/def ::direction #{:up :down :right :left})
(s/def ::speed #{:std :fast})

(defn move-selected
  [direction speed]
  {:pre [(us/valid? ::direction direction)
         (us/valid? ::speed speed)]}
  (MoveSelected. direction speed))

;; --- Move Selected Layer

(defrecord MoveSelectedLayer [loc]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [id (get-in state [:workspace :current])
          selected (get-in state [:workspace id :selected])]
      (simpl/move-layer state selected loc))))

(defn move-selected-layer
  [loc]
  {:pre [(us/valid? ::direction loc)]}
  (MoveSelectedLayer. loc))

;; --- Delete Selected

(defrecord DeleteSelected []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [id (get-in state [:workspace :current])
          selected (get-in state [:workspace id :selected])]
      (rx/from-coll
       (into [(deselect-all)] (map #(uds/delete-shape %) selected))))))

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (DeleteSelected.))

;; --- Change Shape Order (Ordering)

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

;; --- Shape Transformations

(defrecord InitialShapeAlign [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [{:keys [x1 y1] :as shape} (->> (get-in state [:shapes id])
                                         (geom/shape->rect-shape state))
          point (gpt/point x1 y1)]
      (->> (uwrk/align-point point)
           (rx/map (fn [{:keys [x y] :as pt}]
                     (apply-temporal-displacement id (gpt/subtract pt point))))))))

(defn initial-shape-align
  [id]
  {:pre [(uuid? id)]}
  (InitialShapeAlign. id))

;; --- Apply Temporal Displacement

(defrecord ApplyTemporalDisplacement [id delta]
  ptk/UpdateEvent
  (update [_ state]
    (let [pid (get-in state [:workspace :current])
          prev (get-in state [:workspace pid :modifiers id :displacement] (gmt/matrix))
          curr (gmt/translate prev delta)]
      (assoc-in state [:workspace pid :modifiers id :displacement] curr))))

(defn apply-temporal-displacement
  [id pt]
  {:pre [(uuid? id) (gpt/point? pt)]}
  (ApplyTemporalDisplacement. id pt))

;; --- Apply Displacement

(defrecord ApplyDisplacement [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [pid (get-in state [:workspace :current])
          displacement (get-in state [:workspace pid :modifiers id :displacement])]
      (if (gmt/matrix? displacement)
        (rx/of #(simpl/materialize-xfmt % id displacement)
               #(update-in % [:workspace pid :modifiers id] dissoc :displacement)
               ::udp/page-update)
        (rx/empty)))))

(defn apply-displacement
  [id]
  {:pre [(uuid? id)]}
  (ApplyDisplacement. id))

;; --- Apply Temporal Resize Matrix

(deftype ApplyTemporalResize [sid xfmt]
  ptk/UpdateEvent
  (update [_ state]
    (let [pid (get-in state [:workspace :current])]
      (assoc-in state [:workspace pid :modifiers sid :resize] xfmt))))

(defn apply-temporal-resize
  "Attach temporal resize transformation to the shape."
  [id xfmt]
  {:pre [(gmt/matrix? xfmt) (uuid? id)]}
  (ApplyTemporalResize. id xfmt))

;; --- Apply Resize Matrix

(deftype ApplyResize [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [pid (get-in state [:workspace :current])
          resize (get-in state [:workspace pid :modifiers id :resize])]
      (if (gmt/matrix? resize)
        (rx/of #(simpl/materialize-xfmt % id resize)
               #(update-in % [:workspace pid :modifiers id] dissoc :resize)
               ::udp/page-update)
        (rx/empty)))))

(defn apply-resize
  "Apply definitivelly the resize matrix transformation to the shape."
  [id]
  {:pre [(uuid? id)]}
  (ApplyResize. id))

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

(defn select-for-drawing
  [shape]
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            current (get-in state [:workspace pid :drawing-tool])]
        (if (or (nil? shape)
                (= shape current))
          (update-in state [:workspace pid] dissoc :drawing :drawing-tool)
          (update-in state [:workspace pid] assoc
                     :drawing shape
                     :drawing-tool shape))))))

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
;; Server Interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Update Metadata

;; Is a workspace aware wrapper over uxbox.data.pages/UpdateMetadata event.

(defrecord UpdateMetadata [id metadata]
  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (udp/update-metadata id metadata)
           (initialize-alignment id))))

(defn update-metadata
  [id metadata]
  {:pre [(uuid? id) (us/valid? ::udp/metadata metadata)]}
  (UpdateMetadata. id metadata))

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
