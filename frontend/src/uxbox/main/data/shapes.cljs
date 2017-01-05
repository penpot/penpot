;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.shapes
  (:require [cljs.spec :as s :include-macros true]
            [lentes.core :as l]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.lenses :as ul]
            [uxbox.main.geom :as geom]
            [uxbox.main.data.core :refer (worker)]
            [uxbox.main.data.shapes-impl :as impl]
            [uxbox.main.data.pages :as udp]
            [uxbox.util.forms :as sc]
            [uxbox.util.spec :as us]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.router :as r]
            [uxbox.util.rlocks :as rlocks]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.workers :as uw]))

(s/def ::x1 number?)
(s/def ::y1 number?)
(s/def ::x2 number?)
(s/def ::y2 number?)
(s/def ::type keyword?)

(s/def ::rect-shape (s/keys :req-un [::x1 ::y1 ::x2 ::y2 ::type]))

;; --- Shapes CRUD

(defn add-shape
  "Create and add shape to the current selected page."
  [shape]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:workspace :page])
            used-names (map #(get-in state [:shapes % :name]) (get-in state [:pages page :shapes]))
            shape (geom/setup-proportions shape)]
        (impl/assoc-shape-to-page state shape used-names page)))))

(defn delete-shape
  "Remove the shape using its id."
  [id]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:shapes id])]
        (impl/dissoc-shape state shape)))))

(defn update-shape
  "Just updates in place the shape."
  [{:keys [id] :as shape}]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes id] merge shape))))

;; --- Shape Transformations

(declare align-point)

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

(declare apply-temporal-displacement)

(defn initial-align-shape
  [id]
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (let [{:keys [x1 y1] :as shape} (->> (get-in state [:shapes id])
                                           (geom/shape->rect-shape state))
            point1 (gpt/point x1 y1)
            point2 (gpt/add point1 canvas-coords)]
        (->> (align-point point2)
             (rx/map #(gpt/subtract % canvas-coords))
             (rx/map (fn [{:keys [x y] :as pt}]
                       (apply-temporal-displacement id (gpt/subtract pt point1)))))))))

(defn update-rotation
  [sid rotation]
  {:pre [(number? rotation)
         (>= rotation 0)
         (>= 360 rotation)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid]
                 geom/rotate rotation))))

(defn update-size
  "A helper event just for update the position
  of the shape using the width and height attrs
  instread final point of coordinates."
  [sid opts]
  {:pre [(uuid? sid)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid] geom/resize-dim opts))))

;; --- Apply Temporal Displacement

(deftype ApplyTemporalDisplacement [id delta]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [shape (get-in state [:shapes id])
          displ (:tmp-displacement shape (gpt/point 0 0))
          delta (gpt/add displ delta)]
      (assoc-in state [:shapes id :tmp-displacement] delta))))

(defn apply-temporal-displacement
  [id pt]
  {:pre [(uuid? id) (gpt/point? pt)]}
  (ApplyTemporalDisplacement. id pt))

;; --- Apply Displacement

;; TODO: move to shapes-impl ns.

(deftype ApplyDisplacement [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [tmp-displacement type] :as shape} (get-in state [:shapes id])
          xfmt  (gmt/translate-matrix tmp-displacement)]

      (if (= type :group)
        (letfn [(update-item [state id]
                  (let [{:keys [type items] :as shape} (get-in state [:shapes id])]
                    (if (= type :group)
                      (reduce update-item state items)
                      (update-in state [:shapes id]
                                 (fn [shape]
                                   (as-> (dissoc shape :tmp-displacement) $
                                     (geom/transform state $ xfmt)))))))]
          (-> (reduce update-item state (:items shape))
              (update-in [:shapes id] dissoc :tmp-displacement)))

        (update-in state [:shapes id] (fn [shape]
                                        (as-> (dissoc shape :tmp-displacement) $
                                          (geom/transform state $ xfmt))))))))

(defn apply-displacement
  [id]
  {:pre [(uuid? id)]}
  (ApplyDisplacement. id))

;; --- Apply Temporal Resize Matrix

(deftype ApplyTemporalResizeMatrix [id mx]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:shapes id :tmp-resize-xform] mx)))

(defn apply-temporal-resize-matrix
  "Attach temporal resize matrix transformation to the shape."
  [id mx]
  (ApplyTemporalResizeMatrix. id mx))

;; --- Apply Resize Matrix

(declare apply-resize-matrix)

(deftype ApplyResizeMatrix [id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [type tmp-resize-xform]
           :or {tmp-resize-xform (gmt/matrix)}
           :as shape} (get-in state [:shapes id])]
      (if (= type :group)
        (letfn [(update-item [state id]
                  (let [{:keys [type items] :as shape} (get-in state [:shapes id])]
                    (if (= type :group)
                      (reduce update-item state items)
                      (update-in state [:shapes id]
                                 (fn [shape]
                                   (as-> (dissoc shape :tmp-resize-xform) $
                                     (geom/transform state $ tmp-resize-xform)))))))]
          (-> (reduce update-item state (:items shape))
              (update-in [:shapes id] dissoc :tmp-resize-xform)))
        (update-in state [:shapes id] (fn [shape]
                                        (as-> (dissoc shape :tmp-resize-xform) $
                                          (geom/transform state $ tmp-resize-xform))))))))

(defn apply-resize-matrix
  "Apply definitivelly the resize matrix transformation to the shape."
  [id]
  {:pre [(uuid? id)]}
  (ApplyResizeMatrix. id))

(defn update-position
  "Update the start position coordenate of the shape."
  [sid {:keys [x y] :as opts}]
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid] geom/absolute-move opts))))

(defn update-text
  [sid {:keys [content]}]
  {:pre [(string? content)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes sid :content] content))))

;; --- Update Shape Attrs

(declare UpdateAttrs)

(deftype UpdateAttrs [id attrs]
  ptk/WatchEvent
  (watch [_ state stream]
    (println "update-attrs" id attrs)
    (let [{:keys [type] :as shape} (get-in state [:shapes id])]
      (if (= type :group)
        (rx/from-coll (map #(UpdateAttrs. % attrs) (:items shape)))
        (rx/of #(update-in % [:shapes id] merge attrs))))))

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

(s/def ::shape-attrs
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
                   ::proportion-lock
                   ::proportion
                   ::collapsed
                   ::hidden
                   ::blocked
                   ::locked]))

(defn update-attrs
  [id attrs]
  {:pre [(uuid? id) (us/valid? ::shape-attrs attrs)]}
  (let [atts (us/extract attrs ::shape-attrs)]
    (UpdateAttrs. id attrs)))

;; --- Shape Proportions

(defn lock-proportions
  "Mark proportions of the shape locked and save the current
  proportion as additional precalculated property."
  [sid]
  {:pre [(uuid? sid)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [[width height] (-> (get-in state [:shapes sid])
                               (geom/size)
                               (keep [:width :height]))
            proportion (/ width height)]
        (update-in state [:shapes sid] assoc
                   :proportion proportion
                   :proportion-lock true)))))

(defn unlock-proportions
  [sid]
  {:pre [(uuid? sid)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid] assoc
                 :proportion-lock false))))

;; --- Group Collapsing

(defn collapse-shape
  [id]
  {:pre [(uuid? id)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes id] assoc :collapsed true))))

(defn uncollapse-shape
  [id]
  {:pre [(uuid? id)]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes id] assoc :collapsed false))))

;; --- Shape Visibility

(defn hide-shape
  [sid]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes sid :hidden] true))

    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map hide-shape (:items shape))))))))

(defn show-shape
  [sid]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes sid :hidden] false))

    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map show-shape (:items shape))))))))

(defn block-shape
  [sid]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes sid :blocked] true))

    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map block-shape (:items shape))))))))

(defn unblock-shape
  [sid]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes sid :blocked] false))

    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map unblock-shape (:items shape))))))))

(defn lock-shape
  [sid]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes sid :locked] true))

    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map lock-shape (:items shape))))))))

(defn unlock-shape
  [sid]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:shapes sid :locked] false))

    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map unlock-shape (:items shape))))))))

(defn drop-shape
  "Event used in drag and drop for transfer shape
  from one position to an other."
  [sid tid loc]
  {:pre [(not (nil? tid))
         (not (nil? sid))]}
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (impl/drop-shape state sid tid loc))))

(defn select-first-shape
  "Mark a shape selected for drawing in the canvas."
  []
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:workspace :page])
            id (first (get-in state [:pages page :shapes]))]
        (assoc-in state [:workspace :selected] #{id})))))


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

;; --- Select Shapes

(deftype SelectShapesBySelrect [selrect]
  ptk/UpdateEvent
  (update [_ state]
    (let [page (get-in state [:workspace :page])
          shapes (impl/match-by-selrect state page selrect)]
      (assoc-in state [:workspace :selected] shapes))))

(defn select-shapes-by-selrect
  "Select shapes that matches the select rect."
  [selrect]
  {:pre [(us/valid? ::rect-shape selrect)]}
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
    (update-in state [:shapes id :points index] gpt/add delta)))

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  {:pre [(uuid? id) (number? index) (gpt/point? delta)]}
  (UpdatePath. id index delta))

(deftype InitialPathPointAlign [id index]
  ptk/WatchEvent
  (watch [_ state s]
    (let [shape (get-in state [:shapes id])
          point (get-in shape [:points index])
          point (gpt/add point canvas-coords)]
      (->> (align-point point)
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

  ptk/EffectEvent
  (effect [_ state stream]
    (rlocks/acquire! :shape/edition)))

(defn start-edition-mode
  [id]
  {:pre [(uuid? id)]}
  (StartEditionMode. id))

;; --- Events (implicit) (for selected)

(deftype DeselectAll []
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (assoc-in [:workspace :selected] #{})
        (assoc-in [:workspace :edition] nil)
        (assoc-in [:workspace :drawing] nil)))

  ptk/EffectEvent
  (effect [_ state stream]
    (rlocks/release! :shape/edition)))

(defn deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  []
  (DeselectAll.))

(defn group-selected
  []
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :page])
            used-names (map #(get-in state [:shapes % :name])
                            (get-in state [:pages pid :shapes]))
            selected (get-in state [:workspace :selected])]
        (impl/group-shapes state selected used-names pid)))))

(defn degroup-selected
  []
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :page])
            selected (get-in state [:workspace :selected])]
        (impl/degroup-shapes state selected pid)))))

;; TODO: maybe split in two separate events
(defn duplicate-selected
  []
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :page])
            selected (get-in state [:workspace :selected])
            used-names (map #(get-in state [:shapes % :name])
                            (get-in state [:pages pid :shapes]))]
        (impl/duplicate-shapes state selected used-names)))))

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (let [selected (get-in state [:workspace :selected])]
        (rx/from-coll
         (into [(deselect-all)] (map #(delete-shape %) selected)))))))

(deftype UpdateSelectedShapesAttrs [attrs]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [xf (map #(update-attrs % attrs))]
      (rx/from-coll (sequence xf (get-in state [:workspace :selected]))))))

(defn update-selected-shapes-attrs
  [attrs]
  {:pre [(us/valid? ::shape-attrs attrs)]}
  (UpdateSelectedShapesAttrs. attrs))

;; --- Move Selected Layer

(s/def ::direction #{:up :down})

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

(defn alignment-activated?
  [state]
  (let [flags (l/focus ul/workspace-flags state)]
    (and (contains? flags :grid-indexed)
         (contains? flags :grid-alignment)
         (contains? flags :grid))))

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

(deftype MoveSelected [direction speed]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [align? (alignment-activated? state)
          selected (l/focus ul/selected-shapes state)
          page (l/focus ul/selected-page state)
          metadata (merge c/page-metadata (get-in state [:pages page :metadata]))
          distance (get-displacement-distance metadata align?)
          displacement (get-displacement direction speed distance)]
      (rx/concat
       (when align?
         (rx/concat
          (rx/from-coll (map initial-align-shape selected))
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

;; --- Point Alignment (with Grid)

(defn align-point
  [point]
  (let [message {:cmd :grid-align :point point}]
    (->> (uw/ask! worker message)
         (rx/map :point))))
