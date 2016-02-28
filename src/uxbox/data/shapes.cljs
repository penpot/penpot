(ns uxbox.data.shapes
  (:require [bouncer.validators :as v]
            [beicon.core :as rx]
            [uxbox.shapes :as sh]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.state.shapes :as stsh]
            [uxbox.schema :as sc]
            [uxbox.util.time :as time]
            [uxbox.xforms :as xf]
            [uxbox.shapes :as sh]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer (index-of)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +shape-schema+
  {:x [sc/integer]
   :y [sc/integer]
   :width [sc/integer]
   :height [sc/integer]
   :type [sc/required sc/shape-type]})

(def ^:static +shape-size-schema+
  {:width [sc/integer]
   :height [sc/integer]
   :lock [sc/boolean]})

(def ^:static +shape-fill-attrs-schema+
  {:color [sc/color]
   :opacity [sc/number]})

(def ^:static +shape-stroke-attrs-schema+
  {:color [sc/color]
   :width [sc/integer]
   :type [sc/keyword]
   :opacity [sc/number]})

(def ^:static +shape-line-attrs-schema+
  {:x1 [sc/integer]
   :y1 [sc/integer]
   :x2 [sc/integer]
   :y2 [sc/integer]})

(def ^:static +shape-radius-attrs-schema+
  {:rx [sc/integer]
   :ry [sc/integer]})

(def ^:static +shape-position-schema+
  {:x [sc/integer]
   :y [sc/integer]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events (explicit)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-shape
  "Create and add shape to the current selected page."
  [shape]
  (sc/validate! +shape-schema+ shape)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [page (get-in state [:workspace :page])]
        (stsh/assoc-shape-to-page state shape page)))))

(defn delete-shape
  "Remove the shape using its id."
  [id]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id id])]
        (stsh/dissoc-shape state shape)))))

(defn move-shape
  "Mark a shape selected for drawing in the canvas."
  [sid delta]
  {:pre [(gpt/point? delta)]}
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (update-in state [:shapes-by-id sid] sh/move delta)))))

(defn update-line-attrs
  [sid {:keys [x1 y1 x2 y2] :as opts}]
  (sc/validate! +shape-line-attrs-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])
            props (select-keys opts [:x1 :y1 :x2 :y2])
            props' (select-keys shape [:x1 :y1 :x2 :y2])]
        (update-in state [:shapes-by-id sid] sh/initialize
                   (merge props' props))))))

(defn update-rotation
  [sid rotation]
  {:pre [(number? rotation)
         (>= rotation 0)
         (>= 360 rotation)]}
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 sh/rotate rotation))))

(defn update-size
  "A helper event just for update the position
  of the shape using the width and heigt attrs
  instread final point of coordinates.

  WARN: only works with shapes that works
  with height and width such are ::rect"
  [sid {:keys [width height] :as opts}]
  (sc/validate! +shape-size-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])
            size (merge (sh/size shape) opts)]
        (update-in state [:shapes-by-id sid] sh/resize' size)))))

(defn update-position
  "Update the start position coordenate of the shape."
  [sid {:keys [x y] :as opts}]
  (sc/validate! +shape-position-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid] sh/move' opts))))

(defn update-fill-attrs
  [sid {:keys [color opacity] :as opts}]
  (sc/validate! +shape-fill-attrs-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when color {:fill color})
                 (when opacity {:opacity opacity})))))

(defn update-stroke-attrs
  [sid {:keys [color opacity type width] :as opts}]
  (sc/validate! +shape-stroke-attrs-schema+ opts)
  (reify
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
  (sc/validate! +shape-radius-attrs-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when rx {:rx rx})
                 (when ry {:ry ry})))))

(defn hide-shape
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :hidden] true))

    rs/WatchEvent
    (-apply-watch [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :builtin/group)
          (rx/empty)
          (rx/from-coll
           (map hide-shape (:items shape))))))))

(defn show-shape
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :hidden] false))

    rs/WatchEvent
    (-apply-watch [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :builtin/group)
          (rx/empty)
          (rx/from-coll
           (map show-shape (:items shape))))))))

(defn block-shape
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :blocked] true))

    rs/WatchEvent
    (-apply-watch [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :builtin/group)
          (rx/empty)
          (rx/from-coll
           (map block-shape (:items shape))))))))

(defn unblock-shape
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :blocked] false))

    rs/WatchEvent
    (-apply-watch [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :builtin/group)
          (rx/empty)
          (rx/from-coll
           (map unblock-shape (:items shape))))))))

(defn lock-shape
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :locked] true))

    rs/WatchEvent
    (-apply-watch [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :builtin/group)
          (rx/empty)
          (rx/from-coll
           (map lock-shape (:items shape))))))))

(defn unlock-shape
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:shapes-by-id sid :locked] false))

    rs/WatchEvent
    (-apply-watch [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (if-not (= (:type shape) :builtin/group)
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
    rs/UpdateEvent
    (-apply-update [_ state]
      (stsh/drop-shape state sid tid loc))))

