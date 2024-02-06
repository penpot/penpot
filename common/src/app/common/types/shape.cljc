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
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.proportions :as gpr]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.record :as cr]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.transit :as t]
   [app.common.types.color :as ctc]
   [app.common.types.grid :as ctg]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.common.types.shape.blur :as ctsb]
   [app.common.types.shape.export :as ctse]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.path :as ctsp]
   [app.common.types.shape.shadow :as ctss]
   [app.common.types.shape.text :as ctsx]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

(cr/defrecord Shape [id name type x y width height rotation selrect points transform transform-inverse parent-id frame-id])

(defn shape?
  [o]
  (instance? Shape o))

(def stroke-caps-line #{:round :square})
(def stroke-caps-marker #{:line-arrow :triangle-arrow :square-marker :circle-marker :diamond-marker})
(def stroke-caps (set/union stroke-caps-line stroke-caps-marker))

(def shape-types
  #{:frame
    :group
    :bool
    :rect
    :path
    :text
    :circle
    :svg-raw
    :image})

(def blend-modes
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

(sm/define! ::points
  [:vector {:gen/max 4 :gen/min 4} ::gpt/point])

(sm/define! ::fill
  [:map {:title "Fill"}
   [:fill-color {:optional true} ::ctc/rgb-color]
   [:fill-opacity {:optional true} ::sm/safe-number]
   [:fill-color-gradient {:optional true} [:maybe ::ctc/gradient]]
   [:fill-color-ref-file {:optional true} [:maybe ::sm/uuid]]
   [:fill-color-ref-id {:optional true} [:maybe ::sm/uuid]]
   [:fill-image {:optional true} ::ctc/image-color]])

(sm/define! ::stroke
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
   [:stroke-color-gradient {:optional true} ::ctc/gradient]
   [:stroke-image {:optional true} ::ctc/image-color]])

(sm/define! ::shape-base-attrs
  [:map {:title "ShapeMinimalRecord"}
   [:id ::sm/uuid]
   [:name :string]
   [:type [::sm/one-of shape-types]]
   [:selrect ::grc/rect]
   [:points ::points]
   [:transform ::gmt/matrix]
   [:transform-inverse ::gmt/matrix]
   [:parent-id ::sm/uuid]
   [:frame-id ::sm/uuid]])

(sm/define! ::shape-geom-attrs
  [:map {:title "ShapeGeometryAttrs"}
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:height ::sm/safe-number]])

(sm/define! ::shape-attrs
  [:map {:title "ShapeAttrs"}
   [:name {:optional true} :string]
   [:component-id {:optional true}  ::sm/uuid]
   [:component-file {:optional true} ::sm/uuid]
   [:component-root {:optional true} :boolean]
   [:main-instance {:optional true} :boolean]
   [:remote-synced {:optional true} :boolean]
   [:shape-ref {:optional true} ::sm/uuid]
   [:selrect {:optional true} ::grc/rect]
   [:points {:optional true} ::points]
   [:blocked {:optional true} :boolean]
   [:collapsed {:optional true} :boolean]
   [:locked {:optional true} :boolean]
   [:hidden {:optional true} :boolean]
   [:masked-group {:optional true} :boolean]
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
   [:transform {:optional true} ::gmt/matrix]
   [:transform-inverse {:optional true} ::gmt/matrix]
   [:blend-mode {:optional true} [::sm/one-of blend-modes]]
   [:interactions {:optional true}
    [:vector {:gen/max 2} ::ctsi/interaction]]
   [:shadow {:optional true}
    [:vector {:gen/max 1} ::ctss/shadow]]
   [:blur {:optional true} ::ctsb/blur]
   [:grow-type {:optional true}
    [::sm/one-of #{:auto-width :auto-height :fixed}]]])

(sm/define! ::group-attrs
  [:map {:title "GroupAttrs"}
   [:type [:= :group]]
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]])

(sm/define! ::frame-attrs
  [:map {:title "FrameAttrs"}
   [:type [:= :frame]]
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]
   [:hide-fill-on-export {:optional true} :boolean]
   [:show-content {:optional true} :boolean]
   [:hide-in-viewer {:optional true} :boolean]])

(sm/define! ::bool-attrs
  [:map {:title "BoolAttrs"}
   [:type [:= :bool]]
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]

   ;; FIXME: improve this schema
   [:bool-type :keyword]

   [:bool-content
    [:vector {:gen/max 2}
     [:map
      [:command :keyword]
      [:relative {:optional true} :boolean]
      [:prev-pos {:optional true} ::gpt/point]
      [:params {:optional true}
       [:maybe
        [:map-of {:gen/max 5} :keyword ::sm/safe-number]]]]]]])

(sm/define! ::rect-attrs
  [:map {:title "RectAttrs"}
   [:type [:= :rect]]])

(sm/define! ::circle-attrs
  [:map {:title "CircleAttrs"}
   [:type [:= :circle]]])

(sm/define! ::svg-raw-attrs
  [:map {:title "SvgRawAttrs"}
   [:type [:= :svg-raw]]])

(sm/define! ::image-attrs
  [:map {:title "ImageAttrs"}
   [:type [:= :image]]
   [:metadata
    [:map
     [:width :int]
     [:height :int]
     [:mtype {:optional true} [:maybe :string]]
     [:id ::sm/uuid]]]])

(sm/define! ::path-attrs
  [:map {:title "PathAttrs"}
   [:type [:= :path]]
   [:content ::ctsp/content]])

(sm/define! ::text-attrs
  [:map {:title "TextAttrs"}
   [:type [:= :text]]
   [:content {:optional true} [:maybe ::ctsx/content]]])

(sm/define! ::shape-map
  [:multi {:dispatch :type :title "Shape"}
   [:group
    [:and {:title "GroupShape"}
     ::shape-base-attrs
     ::shape-geom-attrs
     ::shape-attrs
     ::group-attrs
     ::ctsl/layout-child-attrs]]

   [:frame
    [:and {:title "FrameShape"}
     ::shape-base-attrs
     ::shape-geom-attrs
     ::frame-attrs
     ::ctsl/layout-attrs
     ::ctsl/layout-child-attrs]]

   [:bool
    [:and {:title "BoolShape"}
     ::shape-base-attrs
     ::shape-attrs
     ::bool-attrs
     ::ctsl/layout-child-attrs]]

   [:rect
    [:and {:title "RectShape"}
     ::shape-base-attrs
     ::shape-geom-attrs
     ::shape-attrs
     ::rect-attrs
     ::ctsl/layout-child-attrs]]

   [:circle
    [:and {:title "CircleShape"}
     ::shape-base-attrs
     ::shape-geom-attrs
     ::shape-attrs
     ::circle-attrs
     ::ctsl/layout-child-attrs]]

   [:image
    [:and {:title "ImageShape"}
     ::shape-base-attrs
     ::shape-geom-attrs
     ::shape-attrs
     ::image-attrs
     ::ctsl/layout-child-attrs]]

   [:svg-raw
    [:and {:title "SvgRawShape"}
     ::shape-base-attrs
     ::shape-geom-attrs
     ::shape-attrs
     ::svg-raw-attrs
     ::ctsl/layout-child-attrs]]

   [:path
    [:and {:title "PathShape"}
     ::shape-base-attrs
     ::shape-attrs
     ::path-attrs
     ::ctsl/layout-child-attrs]]

   [:text
    [:and {:title "TextShape"}
     ::shape-base-attrs
     ::shape-geom-attrs
     ::shape-attrs
     ::text-attrs
     ::ctsl/layout-child-attrs]]])

(sm/define! ::shape
  [:and
   {:title "Shape"
    :gen/gen (->> (sg/generator ::shape-base-attrs)
                  (sg/mcat (fn [{:keys [type] :as shape}]
                             (sg/let [attrs1 (sg/generator ::shape-attrs)
                                      attrs2 (sg/generator ::shape-geom-attrs)
                                      attrs3 (case type
                                               :text    (sg/generator ::text-attrs)
                                               :path    (sg/generator ::path-attrs)
                                               :svg-raw (sg/generator ::svg-raw-attrs)
                                               :image   (sg/generator ::image-attrs)
                                               :circle  (sg/generator ::circle-attrs)
                                               :rect    (sg/generator ::rect-attrs)
                                               :bool    (sg/generator ::bool-attrs)
                                               :group   (sg/generator ::group-attrs)
                                               :frame   (sg/generator ::frame-attrs))]
                               (if (or (= type :path)
                                       (= type :bool))
                                 (merge attrs1 shape attrs3)
                                 (merge attrs1 shape attrs2 attrs3)))))
                  (sg/fmap map->Shape))}
   ::shape-map
   [:fn shape?]])

(def check-shape-attrs!
  (sm/check-fn ::shape-attrs))

(def check-shape!
  (sm/check-fn ::shape))

(defn has-images?
  [{:keys [fills strokes]}]
  (or
   (some :fill-image fills)
   (some :stroke-image strokes)))

;; --- Initialization

(def ^:private minimal-rect-attrs
  {:type :rect
   :name "Rectangle"
   :fills [{:fill-color default-color
            :fill-opacity 1}]
   :strokes []
   :rx 0
   :ry 0})

(def ^:private minimal-image-attrs
  {:type :image
   :rx 0
   :ry 0
   :fills []
   :strokes []})

(def ^:private minimal-frame-attrs
  {:frame-id uuid/zero
   :fills [{:fill-color clr/white
            :fill-opacity 1}]
   :strokes []
   :name "Board"
   :shapes []
   :hide-fill-on-export false})

(def ^:private minimal-circle-attrs
  {:type :circle
   :name "Ellipse"
   :fills [{:fill-color default-color
            :fill-opacity 1}]
   :strokes []})

(def ^:private minimal-group-attrs
  {:type :group
   :name "Group"
   :fills []
   :strokes []
   :shapes []})

(def ^:private minimal-bool-attrs
  {:type :bool
   :name "Bool"
   :fills []
   :strokes []
   :shapes []})

(def ^:private minimal-text-attrs
  {:type :text
   :name "Text"})

(def ^:private minimal-path-attrs
  {:type :path
   :name "Path"
   :fills []
   :strokes [{:stroke-style :solid
              :stroke-alignment :inner
              :stroke-width 2
              :stroke-color clr/black
              :stroke-opacity 1}]})

(def ^:private minimal-svg-raw-attrs
  {:type :svg-raw
   :fills []
   :strokes []})

(def ^:private minimal-multiple-attrs
  {:type :multiple})

(defn- get-minimal-shape
  [type]
  (case type
    :rect minimal-rect-attrs
    :image minimal-image-attrs
    :circle minimal-circle-attrs
    :path minimal-path-attrs
    :frame minimal-frame-attrs
    :bool minimal-bool-attrs
    :group minimal-group-attrs
    :text minimal-text-attrs
    :svg-raw minimal-svg-raw-attrs
    ;; NOTE: used for create ephimeral shapes for multiple selection
    :multiple minimal-multiple-attrs))

(defn- make-minimal-shape
  [type]
  (let [type  (if (= type :curve) :path type)
        attrs (get-minimal-shape type)]

    (cond-> attrs
      (and (not= :path type)
           (not= :bool type))
      (-> (assoc :x 0)
          (assoc :y 0)
          (assoc :width 0.01)
          (assoc :height 0.01))

      :always
      (assoc :id (uuid/next)
             :frame-id uuid/zero
             :parent-id uuid/zero
             :rotation 0)

      :always
      (map->Shape))))

(defn setup-rect
  "Initializes the selrect and points for a shape."
  [{:keys [selrect points] :as shape}]
  (let [selrect (or selrect (gsh/shape->rect shape))
        points  (or points  (grc/rect->points selrect))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))

(defn setup-path
  [{:keys [content selrect points] :as shape}]
  (let [selrect (or selrect
                    (gsh/content->selrect content)
                    (grc/make-rect))
        points  (or points  (grc/rect->points selrect))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))

(defn- setup-image
  [{:keys [metadata] :as shape}]
  (-> shape
      (assoc :proportion (/ (:width metadata)
                            (:height metadata)))
      (assoc :proportion-lock true)))

(defn setup-shape
  "A function that initializes the geometric data of
  the shape. The props must have :x :y :width :height."
  [{:keys [type] :as props}]
  (let [shape (make-minimal-shape type)

        ;; The props can be custom records that does not
        ;; work properly with without-nils, so we first make
        ;; it plain map for proceed
        props (d/without-nils (into {} props))
        shape (merge shape (d/without-nils (into {} props)))
        shape (case (:type shape)
                (:bool :path)  (setup-path shape)
                :image (-> shape setup-rect setup-image)
                (setup-rect shape))]
    (-> shape
        (cond-> (nil? (:transform shape))
          (assoc :transform (gmt/matrix)))
        (cond-> (nil? (:transform-inverse shape))
          (assoc :transform-inverse (gmt/matrix)))
        (gpr/setup-proportions))))

;; --- SHAPE SERIALIZATION

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
