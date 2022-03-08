;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.init
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.common :refer [file-version default-color]]
   [app.common.uuid :as uuid]))

(def root uuid/zero)

(def empty-page-data
  {:options {}
   :name "Page-1"
   :objects
   {root
    {:id root
     :type :frame
     :name "Root Frame"}}})

(def empty-file-data
  {:version file-version
   :pages []
   :pages-index {}})

(def default-shape-attrs
  {})

(def default-frame-attrs
  {:frame-id uuid/zero
   :fills [{:fill-color clr/white
            :fill-opacity 1}]
   :strokes []
   :shapes []
   :hide-fill-on-export false})

(def ^:private minimal-shapes
  [{:type :rect
    :name "Rect-1"
    :fills [{:fill-color default-color
             :fill-opacity 1}]
    :strokes []
    :rx 0
    :ry 0}

   {:type :image
    :rx 0
    :ry 0
    :fills []
    :strokes []}

   {:type :circle
    :name "Circle-1"
    :fills [{:fill-color default-color
             :fill-opacity 1}]
    :strokes []}

   {:type :path
    :name "Path-1"
    :fills []
    :strokes [{:stroke-style :solid
               :stroke-alignment :center
               :stroke-width 2
               :stroke-color clr/black
               :stroke-opacity 1}]}

   {:type :frame
    :name "Artboard-1"
    :fills [{:fill-color clr/white
             :fill-opacity 1}]
    :strokes []
    :stroke-style :none
    :stroke-alignment :center
    :stroke-width 0
    :stroke-color clr/black
    :stroke-opacity 0
    :rx 0
    :ry 0}

   {:type :text
    :name "Text-1"
    :content nil}

   {:type :svg-raw}])

(def empty-selrect
  {:x  0    :y  0
   :x1 0    :y1 0
   :x2 0.01    :y2 0.01
   :width 0.01 :height 0.01})

(defn make-minimal-shape
  [type]
  (let [type (cond (= type :curve) :path
                   :else type)
        shape (d/seek #(= type (:type %)) minimal-shapes)]
    (when-not shape
      (ex/raise :type :assertion
                :code :shape-type-not-implemented
                :context {:type type}))

    (cond-> shape
      :always
      (assoc :id (uuid/next))

      (not= :path (:type shape))
      (assoc :x 0
             :y 0
             :width 0.01
             :height 0.01
             :selrect {:x 0
                       :y 0
                       :x1 0
                       :y1 0
                       :x2 0.01
                       :y2 0.01
                       :width 0.01
                       :height 0.01}))))

(defn make-minimal-group
  [frame-id selection-rect group-name]
  {:id (uuid/next)
   :type :group
   :name group-name
   :shapes []
   :frame-id frame-id
   :x (:x selection-rect)
   :y (:y selection-rect)
   :width (:width selection-rect)
   :height (:height selection-rect)})

(defn make-file-data
  ([file-id]
   (make-file-data file-id (uuid/next)))

  ([file-id page-id]
   (let [pd (assoc empty-page-data
                   :id page-id
                   :name "Page-1")]
     (-> empty-file-data
         (assoc :id file-id)
         (update :pages conj page-id)
         (update :pages-index assoc page-id pd)))))

(defn setup-rect-selrect
  "Initializes the selrect and points for a shape"
  [shape]
  (let [selrect (gsh/rect->selrect shape)
        points  (gsh/rect->points shape)]
    (-> shape
        (assoc :selrect selrect
               :points points))))

(defn- setup-rect
  "A specialized function for setup rect-like shapes."
  [shape {:keys [x y width height]}]
  (-> shape
      (assoc :x x :y y :width width :height height)
      (setup-rect-selrect)))

(defn- setup-image
  [{:keys [metadata] :as shape} props]
  (-> (setup-rect shape props)
      (assoc
       :proportion (/ (:width metadata)
                      (:height metadata))
       :proportion-lock true)))

(defn setup-shape
  "A function that initializes the first coordinates for
  the shape. Used mainly for draw operations."
  ([props]
   (setup-shape {:type :rect} props))

  ([shape props]
   (case (:type shape)
     :image (setup-image shape props)
     (setup-rect shape props))))


