;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.shapes
  (:require [beicon.core :as rx]
            [uxbox.util.uuid :as uuid]
            [uxbox.main.constants :as c]
            [uxbox.common.rstore :as rs]
            [uxbox.common.router :as r]
            [uxbox.common.schema :as sc]
            [uxbox.common.geom :as geom]
            [uxbox.common.geom.point :as gpt]
            [uxbox.common.workers :as uw]
            [uxbox.main.state :as st]
            [uxbox.main.state.shapes :as stsh]
            [uxbox.main.data.core :refer (worker)]
            [uxbox.main.data.pages :as udp]
            [uxbox.util.data :refer (index-of)]))

(defn add-shape
  "Create and add shape to the current selected page."
  [shape]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [page (get-in state [:workspace :page])]
        (stsh/assoc-shape-to-page state shape page)))))

(defn delete-shape
  "Remove the shape using its id."
  [id]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id id])]
        (stsh/dissoc-shape state shape)))))

(defn update-shape
  "Just updates in place the shape."
  [{:keys [id] :as shape}]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id id] merge shape))))

(defn move-shape
  "Move shape using relative position (delta)."
  [sid delta]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (update-in state [:shapes-by-id sid] geom/move delta)))))

(declare align-point)

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

(defn initial-align-shape
  [id]
  (reify
    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id id])
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
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])
            props (select-keys opts [:x1 :y1 :x2 :y2])
            props' (select-keys shape [:x1 :y1 :x2 :y2])]
        (update-in state [:shapes-by-id sid] geom/setup
                   (merge props' props))))))

(defn update-rotation
  [sid rotation]
  {:pre [(number? rotation)
         (>= rotation 0)
         (>= 360 rotation)]}
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 geom/rotate rotation))))

(defn update-size
  "A helper event just for update the position
  of the shape using the width and heigt attrs
  instread final point of coordinates.

  WARN: only works with shapes that works
  with height and width such are ::rect"
  [sid {:keys [width height] :as opts}]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (letfn [(resize [shape {:keys [width height] :as size}]
                (let [x1 (:x1 shape)
                      y1 (:y1 shape)]
                  (assoc shape
                         :x2 (+ x1 width)
                         :y2 (+ y1 height))))]
        (let [shape (get-in state [:shapes-by-id sid])
              size (merge (geom/size shape) opts)]
          (update-in state [:shapes-by-id sid] resize size))))))

(defn update-vertex-position
  [id {:keys [vid delta]}]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id id] geom/move-vertex vid delta))))

(defn initial-vertext-align
  [id vid]
  (reify
    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id id])
            point (geom/get-vertex-point shape vid)
            point (gpt/add point canvas-coords)]
        (->> (align-point point)
             (rx/map #(gpt/subtract % point))
             (rx/map #(update-vertex-position id {:vid vid :delta %})))))))

(defn update-position
  "Update the start position coordenate of the shape."
  [sid {:keys [x y] :as opts}]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid] geom/absolute-move opts))))

(defn update-text
  "Update the start position coordenate of the shape."
  [sid {:keys [content]}]
  {:pre [(string? content)]}
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :content] content))))

(defn update-fill-attrs
  [sid {:keys [color opacity] :as opts}]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when color {:fill color})
                 (when opacity {:fill-opacity opacity})))))

(defn update-font-attrs
  [sid {:keys [family style weight size align
               letter-spacing line-height] :as opts}]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid :font]
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
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when type {:stroke-type type})
                 (when width {:stroke-width width})
                 (when color {:stroke color})
                 (when opacity {:stroke-opacity opacity})))))

(defn update-radius-attrs
  [sid {:keys [rx ry] :as opts}]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when rx {:rx rx})
                 (when ry {:ry ry})))))

(defn hide-shape
  [sid]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :hidden] true))

    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map hide-shape (:items shape))))))))

(defn show-shape
  [sid]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :hidden] false))

    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map show-shape (:items shape))))))))

(defn block-shape
  [sid]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :blocked] true))

    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map block-shape (:items shape))))))))

(defn unblock-shape
  [sid]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :blocked] false))

    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map unblock-shape (:items shape))))))))

(defn lock-shape
  [sid]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :locked] true))

    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :group)
          (rx/empty)
          (rx/from-coll
           (map lock-shape (:items shape))))))))

(defn unlock-shape
  [sid]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :locked] false))

    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [shape (get-in state [:shapes-by-id sid])]
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
    rs/UpdateEvent
    (-apply-update [_ state]
      (stsh/drop-shape state sid tid loc))))

(defn select-first-shape
  "Mark a shape selected for drawing in the canvas."
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [page (get-in state [:workspace :page])
            id (first (get-in state [:pages-by-id page :shapes]))]
        (assoc-in state [:workspace :selected] #{id})))))

(defn select-shape
  "Mark a shape selected for drawing in the canvas."
  [id]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (if (contains? selected id)
          (update-in state [:workspace :selected] disj id)
          (update-in state [:workspace :selected] conj id))))))

;; --- Select Shapes

(defrecord SelectShapes [selrect]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [page (get-in state [:workspace :page])
          shapes (stsh/match-by-selrect state page selrect)]
      (assoc-in state [:workspace :selected] shapes))))

(defn select-shapes
  "Select shapes that matches the select rect."
  [selrect]
  (SelectShapes. selrect))

;; --- Events (implicit) (for selected)

(defn deselect-all
  "Mark a shape selected for drawing in the canvas."
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (-> state
          (assoc-in [:workspace :selected] #{})
          (assoc-in [:workspace :drawing] nil)))))

(defn group-selected
  []
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [pid (get-in state [:workspace :page])
            selected (get-in state [:workspace :selected])]
        (stsh/group-shapes state selected pid)))))

(defn degroup-selected
  []
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [pid (get-in state [:workspace :page])
            selected (get-in state [:workspace :selected])]
        (stsh/degroup-shapes state selected pid)))))

;; TODO: maybe split in two separate events
(defn duplicate-selected
  []
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (stsh/duplicate-shapes state selected)))))

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (reify
    rs/WatchEvent
    (-apply-watch [_ state s]
      (let [selected (get-in state [:workspace :selected])]
        (rx/from-coll
         (into [(deselect-all)] (map #(delete-shape %) selected)))))))

(defn move-selected
  "Move a minimal position unit the selected shapes."
  ([dir] (move-selected dir 1))
  ([dir n]
   {:pre [(contains? #{:up :down :right :left} dir)]}
   (reify
     rs/WatchEvent
     (-apply-watch [_ state s]
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
    rs/WatchEvent
    (-apply-watch [_ state s]
      (rx/from-coll
       (->> (get-in state [:workspace :selected])
            (map #(update-fill-attrs % opts)))))))


(defn update-selected-shapes-stroke
  "Update the fill related attributed on
  selected shapes."
  [opts]
  (reify
    rs/WatchEvent
    (-apply-watch [_ state s]
      (rx/from-coll
       (->> (get-in state [:workspace :selected])
            (map #(update-stroke-attrs % opts)))))))


(defn move-selected-layer
  [loc]
  (reify
    udp/IPageUpdate
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (stsh/move-layer state selected loc)))))

;; --- Point Alignment (with Grid)

(defn align-point
  [point]
  (let [message {:cmd :grid/align :point point}]
    (->> (uw/ask! worker message)
         (rx/map :point))))
