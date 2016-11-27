;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.shapes
  (:require [beicon.core :as rx]
            [uxbox.util.uuid :as uuid]
            [potok.core :as ptk]
            [uxbox.util.router :as r]
            [uxbox.util.forms :as sc]
            [uxbox.util.workers :as uw]
            [uxbox.main.constants :as c]
            [uxbox.main.geom :as geom]
            [uxbox.store :as st]
            [uxbox.main.data.core :refer (worker)]
            [uxbox.main.data.shapes-impl :as impl]
            [uxbox.main.data.pages :as udp]
            [uxbox.util.rlocks :as rlocks]
            [uxbox.util.geom.point :as gpt]))

;; --- Shapes CRUD

(defn add-shape
  "Create and add shape to the current selected page."
  [shape]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:workspace :page])
            shape (geom/setup-proportions shape)]
        (impl/assoc-shape-to-page state shape page)))))

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

(defn move-shape
  "Move shape using relative position (delta)."
  [sid delta]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:shapes sid])]
        (update-in state [:shapes sid] geom/move delta)))))

(declare align-point)

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

(defn initial-align-shape
  [id]
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes id])
            shape (geom/outer-rect state shape)
            point (gpt/point (:x shape) (:y shape))
            point (gpt/add point canvas-coords)]
        (->> (align-point point)
             (rx/map #(gpt/subtract % point))
             (rx/map #(move-shape id %)))))))

(defn update-line-attrs
  [sid {:keys [x1 y1 x2 y2] :as opts}]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:shapes sid])
            props (select-keys opts [:x1 :y1 :x2 :y2])
            props' (select-keys shape [:x1 :y1 :x2 :y2])]
        (update-in state [:shapes sid] geom/setup
                   (merge props' props))))))

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

(defn update-vertex-position
  [id {:keys [vid delta]}]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes id] geom/move-vertex vid delta))))

(defn initial-vertext-align
  [id vid]
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (let [shape (get-in state [:shapes id])
            point (geom/get-vertex-point shape vid)
            point (gpt/add point canvas-coords)]
        (->> (align-point point)
             (rx/map #(gpt/subtract % point))
             (rx/map #(update-vertex-position id {:vid vid :delta %})))))))

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

(defn update-fill-attrs
  [sid {:keys [color opacity] :as opts}]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid]
                 merge
                 (when color {:fill color})
                 (when opacity {:fill-opacity opacity})))))

(defn update-font-attrs
  [sid {:keys [family style weight size align
               letter-spacing line-height] :as opts}]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid :font]
                 merge
                 (when line-height {:line-height line-height})
                 (when letter-spacing {:letter-spacing letter-spacing})
                 (when align {:align align})
                 (when family {:family family})
                 (when style {:style style})
                 (when weight {:weight weight})
                 (when size {:size size})))))

(defn update-stroke-attrs
  [sid {:keys [color opacity type width] :as opts}]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid]
                 merge
                 (when type {:stroke-type type})
                 (when width {:stroke-width width})
                 (when color {:stroke color})
                 (when opacity {:stroke-opacity opacity})))))

(defn update-radius-attrs
  [sid {:keys [rx ry] :as opts}]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:shapes sid]
                 merge
                 (when rx {:rx rx})
                 (when ry {:ry ry})))))


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

(defn select-shape
  "Mark a shape selected for drawing in the canvas."
  [id]
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace :selected])
            state (if (contains? selected id)
                    (update-in state [:workspace :selected] disj id)
                    (update-in state [:workspace :selected] conj id))]
        (update-in state [:workspace :flags] conj :element-options)))))

;; --- Select Shapes

(defrecord SelectShapes [selrect]
  ptk/UpdateEvent
  (update [_ state]
    (let [page (get-in state [:workspace :page])
          shapes (impl/match-by-selrect state page selrect)]
      (assoc-in state [:workspace :selected] shapes))))

(defn select-shapes
  "Select shapes that matches the select rect."
  [selrect]
  (SelectShapes. selrect))

;; --- Update Interaction

(defrecord UpdateInteraction [shape interaction]
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

(defrecord DeleteInteracton [shape id]
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes shape :interactions] dissoc id)))

(defn delete-interaction
  [shape id]
  {:pre [(uuid? id) (uuid? shape)]}
  (DeleteInteracton. shape id))

;; --- Path Modifications

(defrecord UpdatePath [id index delta]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:shapes id :points index] gpt/add delta)))

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  {:pre [(uuid? id) (number? index) (gpt/point? delta)]}
  (UpdatePath. id index delta))

(defrecord InitialPathPointAlign [id index]
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

(defrecord StartEditionMode [id]
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

(defrecord DeselectAll []
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
            selected (get-in state [:workspace :selected])]
        (impl/group-shapes state selected pid)))))

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
      (let [selected (get-in state [:workspace :selected])]
        (impl/duplicate-shapes state selected)))))

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (let [selected (get-in state [:workspace :selected])]
        (rx/from-coll
         (into [(deselect-all)] (map #(delete-shape %) selected)))))))

(defn move-selected
  "Move a minimal position unit the selected shapes."
  ([dir] (move-selected dir 1))
  ([dir n]
   {:pre [(contains? #{:up :down :right :left} dir)]}
   (reify
     ptk/WatchEvent
     (watch [_ state s]
       (let [selected (get-in state [:workspace :selected])
             delta (case dir
                    :up (gpt/point 0 (- n))
                    :down (gpt/point 0 n)
                    :right (gpt/point n 0)
                    :left (gpt/point (- n) 0))]
         (rx/from-coll
          (map #(move-shape % delta) selected)))))))

(defn update-selected-shapes-fill
  "Update the fill related attributed on
  selected shapes."
  [opts]
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (rx/from-coll
       (->> (get-in state [:workspace :selected])
            (map #(update-fill-attrs % opts)))))))


(defn update-selected-shapes-stroke
  "Update the fill related attributed on
  selected shapes."
  [opts]
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (rx/from-coll
       (->> (get-in state [:workspace :selected])
            (map #(update-stroke-attrs % opts)))))))

(defn move-selected-layer
  [loc]
  (reify
    udp/IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (impl/move-layer state selected loc)))))

;; --- Point Alignment (with Grid)

(defn align-point
  [point]
  (let [message {:cmd :grid-align :point point}]
    (->> (uw/ask! worker message)
         (rx/map :point))))
