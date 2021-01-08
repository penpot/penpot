;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.pages.init
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.common.exceptions :as ex]
   [app.common.pages.common :refer [file-version default-color]]))

(def root uuid/zero)

(def empty-page-data
  {:options {}
   :name "Page"
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
   :fill-color "#ffffff"
   :fill-opacity 1
   :shapes []})

(def ^:private minimal-shapes
  [{:type :rect
    :name "Rect"
    :fill-color default-color
    :fill-opacity 1
    :stroke-style :none
    :stroke-alignment :center
    :stroke-width 0
    :stroke-color "#000000"
    :stroke-opacity 0
    :rx 0
    :ry 0}

   {:type :image}

   {:type :circle
    :name "Circle"
    :fill-color default-color
    :fill-opacity 1
    :stroke-style :none
    :stroke-alignment :center
    :stroke-width 0
    :stroke-color "#000000"
    :stroke-opacity 0}

   {:type :path
    :name "Path"
    :fill-color "#000000"
    :fill-opacity 0
    :stroke-style :solid
    :stroke-alignment :center
    :stroke-width 2
    :stroke-color "#000000"
    :stroke-opacity 1}

   {:type :frame
    :name "Artboard"
    :fill-color "#ffffff"
    :fill-opacity 1
    :stroke-style :none
    :stroke-alignment :center
    :stroke-width 0
    :stroke-color "#000000"
    :stroke-opacity 0}

   {:type :text
    :name "Text"
    :content nil}

   {:type :svg-raw}])

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
             :width 1
             :height 1
             :selrect {:x 0
                       :y 0
                       :x1 0
                       :y1 0
                       :x2 1
                       :y2 1
                       :width 1
                       :height 1}))))

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
  ([file-id] (make-file-data file-id(uuid/next)))
  ([file-id page-id]
   (let [
         pd (assoc empty-page-data
                   :id page-id
                   :name "Page-1")]
     (-> empty-file-data
         (assoc :id file-id)
         (update :pages conj page-id)
         (update :pages-index assoc page-id pd)))))
