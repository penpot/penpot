;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.proportions :as gpr]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]
   [app.common.types.grid :as ctg]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.common.types.shape.blur :as ctsb]
   [app.common.types.shape.export :as ctse]
   [app.common.types.shape.interactions :as ctsi]
   ;; FIXME: missing spec -> schema
   #_[app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.shadow :as ctss]
   [app.common.types.shape.text :as ctsx]
   [app.common.uuid :as uuid]
   [app.common.transit :as t]
   [clojure.set :as set]))

(defrecord Shape [id name x y width height selrect points transform transform-inverse parent-id frame-id])

(defn shape?
  [o]
  (instance? Shape o))

(t/add-handlers!
 {:id "shape"
  :class Shape
  :wfn #(into {} %)
  :rfn map->Shape})

#?(:clj
   (fres/add-handlers!
    {:name "penpot/shape"
     :class Shape
     :wfn fres/write-map-like
     :rfn (comp map->Shape fres/read-map-like)}))

(def stroke-caps-line #{:round :square})
(def stroke-caps-marker #{:line-arrow :triangle-arrow :square-marker :circle-marker :diamond-marker})
(def stroke-caps (set/union stroke-caps-line stroke-caps-marker))

(def blend-mode
  #{:normal
    :darken
    :multiply
    :color-burn
    :lighten
    :screen
    :color-dodge
    :overlay
    :soft-light
    :hard-light
    :difference
    :exclusion
    :hue
    :saturation
    :color
    :luminosity})

(def horizontal-constraint-types
  #{:left :right :leftright :center :scale})

(def vertical-constraint-types
  #{:top :bottom :topbottom :center :scale})

(def text-align-types
  #{"left" "right" "center" "justify"})

(sm/def! ::selrect
  [:map {:title "Selrect"}
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]
   [:x1 ::sm/safe-number]
   [:x2 ::sm/safe-number]
   [:y1 ::sm/safe-number]
   [:y2 ::sm/safe-number]
   [:width ::sm/safe-number]
   [:height ::sm/safe-number]])

(sm/def! ::points
  [:vector {:gen/max 5} ::gpt/point])

(sm/def! ::fill
  [:map {:title "Fill" :min 1}
   [:fill-color {:optional true} ::ctc/rgb-color]
   [:fill-opacity {:optional true} ::sm/safe-number]
   [:fill-color-gradient {:optional true} ::ctc/gradient]
   [:fill-color-ref-file {:optional true} [:maybe ::sm/uuid]]
   [:fill-color-ref-id {:optional true} [:maybe ::sm/uuid]]])

(sm/def! ::stroke
  [:map {:title "Stroke"}
   [:stroke-color {:optional true} :string]
   [:stroke-color-ref-file {:optional true} ::sm/uuid]
   [:stroke-color-ref-id {:optional true} ::sm/uuid]
   [:stroke-opacity {:optional true} ::sm/safe-number]
   [:stroke-style {:optional true}
    [::sm/one-of #{:solid :dotted :dashed :mixed :none :svg}]]
   [:stroke-width {:optional true} ::sm/safe-number]
   [:stroke-alignment {:optional true}
    [::sm/one-of #{:center :inner :outer}]]
   [:stroke-cap-start {:optional true}
    [::sm/one-of stroke-caps]]
   [:stroke-cap-end {:optional true}
    [::sm/one-of stroke-caps]]
   [:stroke-color-gradient {:optional true} ::ctc/gradient]])

(sm/def! ::shape-attrs
  [:map {:title "ShapeAttrs"}
   [:name {:optional true} :string]
   [:component-id {:optional true}  ::sm/uuid]
   [:component-file {:optional true} ::sm/uuid]
   [:component-root {:optional true} :boolean]
   [:shape-ref {:optional true} ::sm/uuid]
   [:selrect {:optional true} ::selrect]
   [:points {:optional true} ::points]
   [:blocked {:optional true} :boolean]
   [:collapsed {:optional true} :boolean]
   [:locked {:optional true} :boolean]
   [:hidden {:optional true} :boolean]
   [:masked-group? {:optional true} :boolean]
   [:fills {:optional true}
    [:vector {:gen/max 2} ::fill]]
   [:hide-fill-on-export {:optional true} :boolean]
   [:proportion {:optional true} ::sm/safe-number]
   [:proportion-lock {:optional true} :boolean]
   [:constraints-h {:optional true}
    [::sm/one-of horizontal-constraint-types]]
   [:constraints-v {:optional true}
    [::sm/one-of vertical-constraint-types]]
   [:fixed-scroll {:optional true} :boolean]
   [:rx {:optional true} ::sm/safe-number]
   [:ry {:optional true} ::sm/safe-number]
   [:r1 {:optional true} ::sm/safe-number]
   [:r2 {:optional true} ::sm/safe-number]
   [:r3 {:optional true} ::sm/safe-number]
   [:r4 {:optional true} ::sm/safe-number]
   [:x {:optional true} [:maybe ::sm/safe-number]]
   [:y {:optional true} [:maybe ::sm/safe-number]]
   [:width {:optional true} [:maybe ::sm/safe-number]]
   [:height {:optional true} [:maybe ::sm/safe-number]]
   [:opacity {:optional true} ::sm/safe-number]
   [:grids {:optional true}
    [:vector {:gen/max 2} ::ctg/grid]]
   [:exports {:optional true}
    [:vector {:gen/max 2} ::ctse/export]]
   [:strokes {:optional true}
    [:vector {:gen/max 2} ::stroke]]
   [:transform {:optional true} [:maybe ::gmt/matrix]]
   [:transform-inverse {:optional true} [:maybe ::gmt/matrix]]
   [:blend-mode {:optional true} [::sm/one-of blend-mode]]
   [:interactions {:optional true}
    [:vector {:gen/max 2} ::ctsi/interaction]]
   [:shadow {:optional true}
    [:vector {:gen/max 1} ::ctss/shadow]]
   [:blur {:optional true} ::ctsb/blur]
   [:grow-type {:optional true}
    [::sm/one-of #{:auto-width :auto-height :fixed}]]
   ])

(def valid-shape-attrs?
  (sm/pred-fn ::shape-attrs))

(sm/def! ::group-attrs
  [:map {:title "GroupAttrs"}
   [:type [:= :group]]
   [:id ::sm/uuid]
   [:shapes [:vector {:min 1 :gen/max 10 :gen/min 1} ::sm/uuid]]])

(sm/def! ::frame-attrs
  [:map {:title "FrameAttrs"}
   [:type [:= :frame]]
   [:id ::sm/uuid]
   [:shapes {:optional true} [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]
   [:file-thumbnail {:optional true} :boolean]
   [:hide-fill-on-export {:optional true} :boolean]
   [:show-content {:optional true} :boolean]
   [:hide-in-viewer {:optional true} :boolean]])

(sm/def! ::bool-attrs
  [:map {:title "BoolAttrs"}
   [:type [:= :bool]]
   [:id ::sm/uuid]
   [:shapes [:vector {:min 1 :gen/max 10 :gen/min 1} ::sm/uuid]]

   ;; FIXME: improve this schema
   [:bool-type :keyword]

   ;; FIXME: improve this schema
   [:bool-content
    [:vector {:gen/max 2}
     [:map
      [:command :keyword]
      [:relative :boolean]
      [:params [:map-of {:gen/max 5} :keyword ::sm/safe-number]]]]]])

(sm/def! ::rect-attrs
  [:map {:title "RectAttrs"}
   [:type [:= :rect]]
   [:id ::sm/uuid]])

(sm/def! ::circle-attrs
  [:map {:title "CircleAttrs"}
   [:type [:= :circle]]
   [:id ::sm/uuid]])

(sm/def! ::svg-raw-attrs
  [:map {:title "SvgRawAttrs"}
   [:type [:= :svg-raw]]
   [:id ::sm/uuid]])

(sm/def! ::image-attrs
  [:map {:title "ImageAttrs"}
   [:type [:= :image]]
   [:id ::sm/uuid]
   [:metadata
    [:map
     [:width :int]
     [:height :int]
     [:mtype :string]
     [:id ::sm/uuid]]]])

(sm/def! ::path-attrs
  [:map {:title "PathAttrs"}
   [:type [:= :path]]
   [:id ::sm/uuid]
   [:content
    [:vector
     [:map
      [:command :keyword]
      [:params {:optional true} [:maybe :map]]]]]])

(sm/def! ::text-attrs
  [:map {:title "TextAttrs"}
   [:id ::sm/uuid]
   [:type [:= :text]]
   [:content ::ctsx/content]])

(sm/def! ::shape
  [:multi {:dispatch :type :title "Shape"}
   [:group
    [:merge {:title "GroupShape"}
     ::shape-attrs
     ::group-attrs]]

   [:frame
    [:merge {:title "FrameShape"}
     ::shape-attrs
     ::frame-attrs]]

   [:bool
    [:merge {:title "BoolShape"}
     ::shape-attrs
     ::bool-attrs]]

   [:rect
    [:merge {:title "RectShape"}
     ::shape-attrs
     ::rect-attrs]]

   [:circle
    [:merge {:title "CircleShape"}
     ::shape-attrs
     ::circle-attrs]]

   [:image
    [:merge {:title "ImageShape"}
     ::shape-attrs
     ::image-attrs]]

   [:svg-raw
    [:merge {:title "SvgRawShape"}
     ::shape-attrs
     ::svg-raw-attrs]]

   [:path
    [:merge {:title "PathShape"}
     ::shape-attrs
     ::path-attrs]]

   [:text
    [:merge {:title "TextShape"}
     ::shape-attrs
     ::text-attrs]]
   ])

;; --- Initialization

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
    :name "Rectangle"
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
    :name "Ellipse"
    :fills [{:fill-color default-color
             :fill-opacity 1}]
    :strokes []}

   {:type :path
    :name "Path"
    :fills []
    :strokes [{:stroke-style :solid
               :stroke-alignment :center
               :stroke-width 2
               :stroke-color clr/black
               :stroke-opacity 1}]}

   {:type :frame
    :name "Board"
    :fills [{:fill-color clr/white
             :fill-opacity 1}]
    :strokes []
    :shapes []}

   {:type :bool
    :name "Bool"
    :shapes []}

   {:type :group
    :name "Group"
    :shapes []}

   {:type :text
    :name "Text"
    :content nil}

   {:type :svg-raw
    :fills []
    :strokes []}])

(def empty-selrect
  (gsh/map->Rect
   {:x  0    :y  0
    :x1 0    :y1 0
    :x2 0.01 :y2 0.01
    :width 0.01
    :height 0.01}))

(defn- make-minimal-shape
  [type]
  (let [type  (if (= type :curve) :path type)
        attrs (d/seek #(= type (:type %)) minimal-shapes)]

    (dm/verify!
     "expected a valid shape type"
     (map? attrs))

    (cond-> attrs
      (not= :path type)
      (-> (assoc :x 0)
          (assoc :y 0)
          (assoc :width 0.01)
          (assoc :height 0.01))

      :always
      (assoc :id (uuid/next))

      :always
      (map->Shape))))

(defn setup-rect
  "Initializes the selrect and points for a shape."
  [{:keys [selrect points] :as shape}]
  (let [selrect (or selrect (gsh/rect->selrect shape))
        points  (or points  (gsh/rect->points selrect))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))

(defn setup-path
  [{:keys [content selrect points] :as shape}]
  (let [selrect (or selrect (gsh/content->selrect content))
        points  (or points  (gsh/rect->points selrect))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))

(defn- setup-image
  [{:keys [metadata] :as shape}]
  (-> shape
      (assoc :metadata metadata)
      (assoc :proportion (/ (:width metadata)
                            (:height metadata)))
      (assoc :proportion-lock true)))

(defn setup-shape
  "A function that initializes the geometric data of
  the shape. The props must have :x :y :width :height."
  [{:keys [type] :as props}]
  (let [shape (make-minimal-shape type)
        shape (merge shape (d/without-nils props))
        shape (map->Shape shape)
        shape (case (:type shape)
                :path  (setup-path shape)
                :image (-> shape setup-rect setup-image)
                (setup-rect shape))]
    (-> shape
        (cond-> (nil? (:transform shape))
          (assoc :transform (gmt/matrix)))
        (cond-> (nil? (:transform-inverse shape))
          (assoc :transform-inverse (gmt/matrix)))
        (gpr/setup-proportions))))
