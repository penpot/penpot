;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.shapes
  (:require [cljs.spec.alpha :as s :include-macros true]
            [lentes.core :as l]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.refs :as refs]
            [uxbox.main.lenses :as ul]
            [uxbox.main.geom :as geom]
            [uxbox.main.workers :as uwrk]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.shapes-impl :as impl]
            [uxbox.main.user-events :as uev]
            [uxbox.util.data :refer [dissoc-in]]
            [uxbox.util.forms :as sc]
            [uxbox.util.spec :as us]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.router :as r]
            [uxbox.util.uuid :as uuid]))

;; --- Specs

(s/def ::fill-color us/color?)
(s/def ::fill-opacity number?)
(s/def ::line-height number?)
(s/def ::letter-spacing number?)
(s/def ::text-align #{"left" "right" "center" "justify"})
(s/def ::font-family string?)
(s/def ::font-style string?)
(s/def ::font-weight string?)
(s/def ::font-size number?)
(s/def ::stroke-style #{:none :solid :dotted :dashed :mixed})
(s/def ::stroke-width number?)
(s/def ::stroke-color us/color?)
(s/def ::stroke-opacity number?)
(s/def ::rx number?)
(s/def ::ry number?)
(s/def ::proportion number?)
(s/def ::proportion-lock boolean?)
(s/def ::collapsed boolean?)
(s/def ::hidden boolean?)
(s/def ::blocked boolean?)
(s/def ::locked boolean?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::x1 number?)
(s/def ::y1 number?)
(s/def ::x2 number?)
(s/def ::y2 number?)

(s/def ::attributes
  (s/keys :opt-un [::fill-color
                   ::fill-opacity
                   ::line-height
                   ::letter-spacing
                   ::text-align
                   ::font-family
                   ::font-style
                   ::font-weight
                   ::font-size
                   ::stroke-style
                   ::stroke-width
                   ::stroke-color
                   ::stroke-opacity
                   ::rx ::ry
                   ::x1 ::x2
                   ::y1 ::y2
                   ::proportion-lock
                   ::proportion
                   ::collapsed
                   ::hidden
                   ::blocked
                   ::locked]))

(s/def ::id uuid?)
(s/def ::page uuid?)
(s/def ::type #{:rect
                :group
                :path
                :circle
                :image
                :text})

(s/def ::shape
  (s/merge (s/keys ::req-un [::id ::page ::type]) ::attributes))

(s/def ::rect-like-shape
  (s/keys :req-un [::x1 ::y1 ::x2 ::y2 ::type]))

(s/def ::direction #{:up :down :right :left})
(s/def ::speed #{:std :fast})

;; --- Shapes CRUD

(deftype AddShape [data]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [shape (geom/setup-proportions data)
          page (get-in state [:workspace :page])]
      (impl/assoc-shape-to-page state shape page))))

(defn add-shape
  [data]
  {:pre [(us/valid? ::shape data)]}
  (AddShape. data))

;; --- Delete Shape

(deftype DeleteShape [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [shape (get-in state [:shapes id])]
      (impl/dissoc-shape state shape))))

(defn delete-shape
  "Remove the shape using its id."
  [id]
  {:pre [(uuid? id)]}
  (DeleteShape. id))

;; --- Rename Shape

(deftype RenameShape [id name]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:shapes id :name] name)))

(defn rename-shape
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (RenameShape. id name))

;; --- Shape Transformations

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

(declare apply-temporal-displacement)

(deftype InitialShapeAlign [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [{:keys [x1 y1] :as shape} (->> (get-in state [:shapes id])
                                         (geom/shape->rect-shape state))
          point1 (gpt/point x1 y1)
          point2 (gpt/add point1 canvas-coords)]
      (->> (uwrk/align-point point2)
           (rx/map #(gpt/subtract % canvas-coords))
           (rx/map (fn [{:keys [x y] :as pt}]
                     (apply-temporal-displacement id (gpt/subtract pt point1))))))))

(defn initial-shape-align
  [id]
  {:pre [(uuid? id)]}
  (InitialShapeAlign. id))

;; --- Update Rotation

(deftype UpdateShapeRotation [id rotation]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:shapes id :rotation] rotation)))

(defn update-rotation
  [id rotation]
  {:pre [(uuid? id)
         (number? rotation)
         (>= rotation 0)
         (>= 360 rotation)]}
  (UpdateShapeRotation. id rotation))

;; --- Update Dimensions

(deftype UpdateDimensions [id dimensions]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes id] geom/resize-dim dimensions)))

(s/def ::update-dimensions-opts
  (s/keys :opt-un [::width ::height]))

(defn update-dimensions
  "A helper event just for update the position
  of the shape using the width and height attrs
  instread final point of coordinates."
  [id opts]
  {:pre [(uuid? id) (us/valid? ::update-dimensions-opts opts)]}
  (UpdateDimensions. id opts))

;; --- Apply Temporal Displacement

(deftype ApplyTemporalDisplacement [id delta]
  ptk/UpdateEvent
  (update [_ state]
    (let [prev (get-in state [:workspace :modifiers id :displacement] (gmt/matrix))
          curr (gmt/translate prev delta)]
      (assoc-in state [:workspace :modifiers id :displacement] curr))))

(defn apply-temporal-displacement
  [id pt]
  {:pre [(uuid? id) (gpt/point? pt)]}
  (ApplyTemporalDisplacement. id pt))

;; --- Apply Displacement

(deftype ApplyDisplacement [id]
  udp/IPageUpdate
  ptk/WatchEvent
  (watch [_ state stream]
    (let [displacement (get-in state [:workspace :modifiers id :displacement])]
      (if (gmt/matrix? displacement)
        (rx/of #(impl/materialize-xfmt % id displacement)
               #(update-in % [:workspace :modifiers id] dissoc :displacement)
               ::udp/page-update)
        (rx/empty)))))

(defn apply-displacement
  [id]
  {:pre [(uuid? id)]}
  (ApplyDisplacement. id))

;; --- Apply Temporal Resize Matrix

(deftype ApplyTemporalResize [id xfmt]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :modifiers id :resize] xfmt)))

(defn apply-temporal-resize
  "Attach temporal resize transformation to the shape."
  [id xfmt]
  {:pre [(gmt/matrix? xfmt) (uuid? id)]}
  (ApplyTemporalResize. id xfmt))

;; --- Apply Resize Matrix

(deftype ApplyResize [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [resize (get-in state [:workspace :modifiers id :resize])]
      (if (gmt/matrix? resize)
        (rx/of #(impl/materialize-xfmt % id resize)
               #(update-in % [:workspace :modifiers id] dissoc :resize)
               ::udp/page-update)
        (rx/empty)))))

(defn apply-resize
  "Apply definitivelly the resize matrix transformation to the shape."
  [id]
  {:pre [(uuid? id)]}
  (ApplyResize. id))

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

;; --- Update Shape Text

(deftype UpdateShapeTextContent [id text]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:shapes id :content] text)))

(defn update-text
  [id text]
  {:pre [(uuid? id) (string? text)]}
  (UpdateShapeTextContent. id text))

;; --- Update Shape Attrs

(declare UpdateAttrs)

(deftype UpdateAttrs [id attrs]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [{:keys [type] :as shape} (get-in state [:shapes id])]
      (if (= type :group)
        (rx/from-coll (map #(UpdateAttrs. % attrs) (:items shape)))
        (rx/of #(update-in % [:shapes id] merge attrs))))))

(defn update-attrs
  [id attrs]
  {:pre [(uuid? id) (us/valid? ::attributes attrs)]}
  (let [atts (us/extract attrs ::attributes)]
    (UpdateAttrs. id attrs)))

;; --- Shape Proportions

(deftype LockShapeProportions [id]
  udp/IPageUpdate
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

;; --- Group Collapsing

(deftype CollapseGroupShape [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes id] assoc :collapsed true)))

(defn collapse-shape
  [id]
  {:pre [(uuid? id)]}
  (CollapseGroupShape. id))

(deftype UncollapseGroupShape [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes id] assoc :collapsed false)))

(defn uncollapse-shape
  [id]
  {:pre [(uuid? id)]}
  (UncollapseGroupShape. id))

;; --- Shape Visibility

(deftype HideShape [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (letfn [(mark-hidden [state id]
              (let [shape (get-in state [:shapes id])]
                (if (= :group (:type shape))
                  (as-> state $
                    (assoc-in $ [:shapes id :hidden] true)
                    (reduce mark-hidden $ (:items shape)))
                  (assoc-in state [:shapes id :hidden] true))))]
      (mark-hidden state id))))

(defn hide-shape
  [id]
  {:pre [(uuid? id)]}
  (HideShape. id))

(deftype ShowShape [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (letfn [(mark-visible [state id]
              (let [shape (get-in state [:shapes id])]
                (if (= :group (:type shape))
                  (as-> state $
                    (assoc-in $ [:shapes id :hidden] false)
                    (reduce mark-visible $ (:items shape)))
                  (assoc-in state [:shapes id :hidden] false))))]
      (mark-visible state id))))

(defn show-shape
  [id]
  {:pre [(uuid? id)]}
  (ShowShape. id))

;; --- Shape Blocking

(deftype BlockShape [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (letfn [(mark-blocked [state id]
              (let [shape (get-in state [:shapes id])]
                (if (= :group (:type shape))
                  (as-> state $
                    (assoc-in $ [:shapes id :blocked] true)
                    (reduce mark-blocked $ (:items shape)))
                  (assoc-in state [:shapes id :blocked] true))))]
      (mark-blocked state id))))

(defn block-shape
  [id]
  {:pre [(uuid? id)]}
  (BlockShape. id))

(deftype UnblockShape [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (letfn [(mark-unblocked [state id]
              (let [shape (get-in state [:shapes id])]
                (if (= :group (:type shape))
                  (as-> state $
                    (assoc-in $ [:shapes id :blocked] false)
                    (reduce mark-unblocked $ (:items shape)))
                  (assoc-in state [:shapes id :blocked] false))))]
      (mark-unblocked state id))))

(defn unblock-shape
  [id]
  {:pre [(uuid? id)]}
  (UnblockShape. id))

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

;; --- Drop Shape

(deftype DropShape [sid tid loc]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (impl/drop-shape state sid tid loc)))

(defn drop-shape
  "Event used in drag and drop for transfer shape
  from one position to an other."
  [sid tid loc]
  {:pre [(uuid? sid)
         (uuid? tid)
         (keyword? loc)]}
  (DropShape. sid tid loc))

;; --- Select First Shape

(deftype SelectFirstShape []
  ptk/UpdateEvent
  (update [_ state]
    (let [page (get-in state [:workspace :page])
          id (first (get-in state [:pages page :shapes]))]
      (assoc-in state [:workspace :selected] #{id}))))

(defn select-first-shape
  "Mark a shape selected for drawing in the canvas."
  []
  (SelectFirstShape.))

;; --- Mark Shape Selected

(deftype SelectShape [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:workspace :selected])
          state (if (contains? selected id)
                  (update-in state [:workspace :selected] disj id)
                  (update-in state [:workspace :selected] conj id))]
      (update-in state [:workspace :flags] conj :element-options))))

(defn select-shape
  "Mark a shape selected for drawing in the canvas."
  [id]
  {:pre [(uuid? id)]}
  (SelectShape. id))

;; --- Select Shapes (By selrect)

(deftype SelectShapesBySelrect [selrect]
  ptk/UpdateEvent
  (update [_ state]
    (let [page (get-in state [:workspace :page])
          shapes (impl/match-by-selrect state page selrect)]
      (assoc-in state [:workspace :selected] shapes))))

(defn select-shapes-by-selrect
  "Select shapes that matches the select rect."
  [selrect]
  {:pre [(us/valid? ::rect-like-shape selrect)]}
  (SelectShapesBySelrect. selrect))

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

(deftype InitialPathPointAlign [id index]
  ptk/WatchEvent
  (watch [_ state s]
    (let [shape (get-in state [:shapes id])
          point (get-in shape [:segments index])
          point (gpt/add point canvas-coords)]
      (->> (uwrk/align-point point)
           (rx/map #(gpt/subtract % point))
           (rx/map #(update-path id index %))))))

(defn initial-path-point-align
  "Event responsible of align a specified point of the
  shape by `index` with the grid."
  [id index]
  {:pre [(uuid? id)
         (number? index)
         (not (neg? index))]}
  (InitialPathPointAlign. id index))

;; --- Start shape "edition mode"

(deftype StartEditionMode [id]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :edition] id))

  ptk/WatchEvent
  (watch [_ state stream]
    ;; Stop edition on interrupt event
    (->> stream
         (rx/filter #(= % ::uev/interrupt))
         (rx/take 1)
         (rx/map (fn [_] #(dissoc-in % [:workspace :edition]))))))

(defn start-edition-mode
  [id]
  {:pre [(uuid? id)]}
  (StartEditionMode. id))

;; --- Events (implicit) (for selected)

(deftype DeselectAll []
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :selected] #{}))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/just ::uev/interrupt)))

(defn deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  []
  (DeselectAll.))

;; --- Group Selected Shapes

(deftype GroupSelectedShapes []
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [id (get-in state [:workspace :page])
          selected (get-in state [:workspace :selected])]
      (assert (not (empty? selected)) "selected set is empty")
      (assert (uuid? id) "selected page is not an uuid")
      (impl/group-shapes state selected id))))

(defn group-selected
  []
  (GroupSelectedShapes.))

;; --- Ungroup Selected Shapes

(deftype UngroupSelectedShapes []
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [id (get-in state [:workspace :page])
          selected (get-in state [:workspace :selected])]
      (assert (not (empty? selected)) "selected set is empty")
      (assert (uuid? id) "selected page is not an uuid")
      (impl/degroup-shapes state selected id))))

(defn ungroup-selected
  []
  (UngroupSelectedShapes.))

;; --- Duplicate Selected

(deftype DuplicateSelected []
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:workspace :selected])]
      (impl/duplicate-shapes state selected))))

(defn duplicate-selected
  []
  (DuplicateSelected.))

;; --- Delete Selected

(deftype DeleteSelected []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:workspace :selected])]
      (rx/from-coll
       (into [(deselect-all)] (map #(delete-shape %) selected))))))

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (DeleteSelected.))

(deftype UpdateSelectedShapesAttrs [attrs]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [xf (map #(update-attrs % attrs))]
      (rx/from-coll (sequence xf (get-in state [:workspace :selected]))))))

(defn update-selected-shapes-attrs
  [attrs]
  {:pre [(us/valid? ::attributes attrs)]}
  (UpdateSelectedShapesAttrs. attrs))

;; --- Move Selected Layer

(deftype MoveSelectedLayer [loc]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:workspace :selected])]
      (impl/move-layer state selected loc))))

(defn move-selected-layer
  [loc]
  {:pre [(us/valid? ::direction loc)]}
  (MoveSelectedLayer. loc))

;; --- Move Selected

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

;; --- Move Selected

;; Event used for apply displacement transformation
;; to the selected shapes throught the keyboard shortcuts.

(deftype MoveSelected [direction speed]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [{:keys [page selected]} (:workspace state)
          align? (refs/alignment-activated? state)
          metadata (merge c/page-metadata (get-in state [:pages page :metadata]))
          distance (get-displacement-distance metadata align?)
          displacement (get-displacement direction speed distance)]
      (rx/concat
       (when align?
         (rx/concat
          (rx/from-coll (map initial-shape-align selected))
          (rx/from-coll (map apply-displacement selected))))
       (rx/from-coll (map #(apply-temporal-displacement % displacement) selected))
       (rx/from-coll (map apply-displacement selected))))))

(defn move-selected
  [direction speed]
  {:pre [(us/valid? ::direction direction)
         (us/valid? ::speed speed)]}
  (MoveSelected. direction speed))
