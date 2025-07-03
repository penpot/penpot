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
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.proportions :as gpr]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.record :as cr]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.text :as txt]
   [app.common.transit :as t]
   [app.common.types.color :as types.color]
   [app.common.types.fill :refer [schema:fill]]
   [app.common.types.grid :as ctg]
   [app.common.types.path :as path]
   [app.common.types.path.segment :as path.segment]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.common.types.shape.blur :as ctsb]
   [app.common.types.shape.export :as ctse]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.shadow :as ctss]
   [app.common.types.shape.text :as ctsx]
   [app.common.types.token :as cto]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

(defonce ^:dynamic *wasm-sync* false)

(defonce wasm-enabled? false)
(defonce wasm-create-shape (constantly nil))

;; Marker protocol
(defprotocol IShape)

(cr/defrecord Shape [id name type x y width height rotation selrect points
                     transform transform-inverse parent-id frame-id flip-x flip-y]
  IShape)

(defn shape?
  [o]
  #?(:cljs (implements? IShape o)
     :clj  (instance? Shape o)))

(defn create-shape
  "A low level function that creates a Shape data structure
  from a attrs map without performing other transformations"
  [attrs]
  #?(:cljs (if ^boolean wasm-enabled?
             (^function wasm-create-shape attrs)
             (map->Shape attrs))
     :clj  (map->Shape attrs)))

(def stroke-caps-line #{:round :square})
(def stroke-caps-marker #{:line-arrow :triangle-arrow :square-marker :circle-marker :diamond-marker})
(def stroke-caps (conj (set/union stroke-caps-line stroke-caps-marker) nil))

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

(def bool-types
  #{:union
    :difference
    :exclude
    :intersection})

(def grow-types
  #{:auto-width
    :auto-height
    :fixed})

(def schema:points
  [:vector {:gen/max 4 :gen/min 4} ::gpt/point])

;; FIXME: the register is necessary until this is moved to a separated
;; ns because it is used on shapes.text
(def valid-stroke-attrs
  "A set used for proper check if color should contain only one of the
  attrs listed in this set."
  #{:stroke-image :stroke-color :stroke-color-gradient})

(defn has-valid-stroke-attrs?
  "Check if color has correct color attrs"
  [color]
  (let [attrs  (set (keys color))
        result (set/intersection attrs valid-stroke-attrs)]
    (= 1 (count result))))

(def schema:stroke-attrs
  [:map {:title "StrokeAttrs" :closed true}
   [:stroke-color-ref-file {:optional true} ::sm/uuid]
   [:stroke-color-ref-id {:optional true} ::sm/uuid]
   [:stroke-opacity {:optional true} ::sm/safe-number]
   [:stroke-style {:optional true}
    [::sm/one-of #{:solid :dotted :dashed :mixed}]]
   [:stroke-width {:optional true} ::sm/safe-number]
   [:stroke-alignment {:optional true}
    [::sm/one-of #{:center :inner :outer}]]
   [:stroke-cap-start {:optional true}
    [::sm/one-of stroke-caps]]
   [:stroke-cap-end {:optional true}
    [::sm/one-of stroke-caps]]
   [:stroke-color {:optional true} types.color/schema:hex-color]
   [:stroke-color-gradient {:optional true} types.color/schema:gradient]
   [:stroke-image {:optional true} types.color/schema:image]])

(def stroke-attrs
  "A set of attrs that corresponds to stroke data type"
  (sm/keys schema:stroke-attrs))

(def schema:stroke
  (sm/register!
   ^{::sm/type ::stroke}
   [:and schema:stroke-attrs
    [:fn has-valid-stroke-attrs?]]))

(def check-stroke
  (sm/check-fn schema:stroke))

(def schema:shape-base-attrs
  [:map {:title "ShapeMinimalRecord"}
   [:id ::sm/uuid]
   [:name :string]
   [:type [::sm/one-of shape-types]]
   [:selrect ::grc/rect]
   [:points schema:points]
   [:transform ::gmt/matrix]
   [:transform-inverse ::gmt/matrix]
   [:parent-id ::sm/uuid]
   [:frame-id ::sm/uuid]])

(def schema:shape-geom-attrs
  [:map {:title "ShapeGeometryAttrs"}
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:height ::sm/safe-number]])

(def schema:shape-generic-attrs
  [:map {:title "ShapeAttrs"}
   [:page-id {:optional true} ::sm/uuid]
   [:component-id {:optional true}  ::sm/uuid]
   [:component-file {:optional true} ::sm/uuid]
   [:component-root {:optional true} :boolean]
   [:main-instance {:optional true} :boolean]
   [:remote-synced {:optional true} :boolean]
   [:shape-ref {:optional true} ::sm/uuid]
   [:touched {:optional true} [:maybe [:set :keyword]]]
   [:blocked {:optional true} :boolean]
   [:collapsed {:optional true} :boolean]
   [:locked {:optional true} :boolean]
   [:hidden {:optional true} :boolean]
   [:masked-group {:optional true} :boolean]
   [:fills {:optional true}
    [:vector {:gen/max 2} schema:fill]]
   [:proportion {:optional true} ::sm/safe-number]
   [:proportion-lock {:optional true} :boolean]
   [:constraints-h {:optional true}
    [::sm/one-of horizontal-constraint-types]]
   [:constraints-v {:optional true}
    [::sm/one-of vertical-constraint-types]]
   [:fixed-scroll {:optional true} :boolean]
   [:r1 {:optional true} ::sm/safe-number]
   [:r2 {:optional true} ::sm/safe-number]
   [:r3 {:optional true} ::sm/safe-number]
   [:r4 {:optional true} ::sm/safe-number]
   [:opacity {:optional true} ::sm/safe-number]
   [:grids {:optional true}
    [:vector {:gen/max 2} ::ctg/grid]]
   [:exports {:optional true}
    [:vector {:gen/max 2} ::ctse/export]]
   [:strokes {:optional true}
    [:vector {:gen/max 2} schema:stroke]]
   [:blend-mode {:optional true}
    [::sm/one-of blend-modes]]
   [:interactions {:optional true}
    [:vector {:gen/max 2} ::ctsi/interaction]]
   [:shadow {:optional true}
    [:vector {:gen/max 1} ctss/schema:shadow]]
   [:blur {:optional true} ::ctsb/blur]
   [:grow-type {:optional true}
    [::sm/one-of grow-types]]
   [:applied-tokens {:optional true} cto/schema:applied-tokens]
   [:plugin-data {:optional true} ::ctpg/plugin-data]])

(def schema:group-attrs
  [:map {:title "GroupAttrs"}
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]])

(def ^:private schema:frame-attrs
  [:map {:title "FrameAttrs"}
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]
   [:hide-fill-on-export {:optional true} :boolean]
   [:show-content {:optional true} :boolean]
   [:hide-in-viewer {:optional true} :boolean]])

(def ^:private schema:bool-attrs
  [:map {:title "BoolAttrs"}
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]
   [:bool-type [::sm/one-of bool-types]]
   [:content path/schema:content]])

(def ^:private schema:rect-attrs
  [:map {:title "RectAttrs"}])

(def ^:private schema:circle-attrs
  [:map {:title "CircleAttrs"}])

(def ^:private schema:svg-raw-attrs
  [:map {:title "SvgRawAttrs"}])

(def schema:image-attrs
  [:map {:title "ImageAttrs"}
   [:metadata
    [:map
     [:width {:gen/gen (sg/small-int :min 1)} ::sm/int]
     [:height {:gen/gen (sg/small-int :min 1)} ::sm/int]
     [:mtype {:optional true
              :gen/gen (sg/elements ["image/jpeg"
                                     "image/png"])}
      [:maybe :string]]
     [:id ::sm/uuid]]]])

(def ^:private schema:path-attrs
  [:map {:title "PathAttrs"}
   [:content path/schema:content]])

(def ^:private schema:text-attrs
  [:map {:title "TextAttrs"}
   [:content {:optional true} [:maybe ::ctsx/content]]])

(defn- decode-shape
  [o]
  (if (map? o)
    (create-shape o)
    o))

(defn- shape-generator
  "Get the shape generator."
  []
  (->> (sg/generator schema:shape-base-attrs)
       (sg/mcat (fn [{:keys [type] :as shape}]
                  (sg/let [attrs1 (sg/generator schema:shape-generic-attrs)
                           attrs2 (sg/generator schema:shape-geom-attrs)
                           attrs3 (case type
                                    :text    (sg/generator schema:text-attrs)
                                    :path    (sg/generator schema:path-attrs)
                                    :svg-raw (sg/generator schema:svg-raw-attrs)
                                    :image   (sg/generator schema:image-attrs)
                                    :circle  (sg/generator schema:circle-attrs)
                                    :rect    (sg/generator schema:rect-attrs)
                                    :bool    (sg/generator schema:bool-attrs)
                                    :group   (sg/generator schema:group-attrs)
                                    :frame   (sg/generator schema:frame-attrs))]
                    (if (or (= type :path)
                            (= type :bool))
                      (merge attrs1 shape attrs3)
                      (merge attrs1 shape attrs2 attrs3)))))
       (sg/fmap create-shape)))

(def schema:shape-attrs
  [:multi {:dispatch :type
           :decode/json (fn [shape]
                          (update shape :type keyword))
           :title "Shape"}
   [:group
    [:merge {:title "GroupShape"}
     ctsl/schema:layout-child-attrs
     schema:group-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:frame
    [:merge {:title "FrameShape"}
     ctsl/schema:layout-child-attrs
     ctsl/schema:layout-attrs
     schema:frame-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs
     ::ctv/variant-shape
     ::ctv/variant-container]]

   [:bool
    [:merge {:title "BoolShape"}
     ctsl/schema:layout-child-attrs
     schema:bool-attrs
     schema:shape-generic-attrs
     schema:shape-base-attrs]]

   [:rect
    [:merge {:title "RectShape"}
     ctsl/schema:layout-child-attrs
     schema:rect-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:circle
    [:merge {:title "CircleShape"}
     ctsl/schema:layout-child-attrs
     schema:circle-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:image
    [:merge {:title "ImageShape"}
     ctsl/schema:layout-child-attrs
     schema:image-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:svg-raw
    [:merge {:title "SvgRawShape"}
     ctsl/schema:layout-child-attrs
     schema:svg-raw-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:path
    [:merge {:title "PathShape"}
     ctsl/schema:layout-child-attrs
     schema:path-attrs
     schema:shape-generic-attrs
     schema:shape-base-attrs]]

   [:text
    [:merge {:title "TextShape"}
     ctsl/schema:layout-child-attrs
     schema:text-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]])

(def schema:shape
  (sm/register!
   ^{::sm/type ::shape}
   [:and {:title "Shape"
          :gen/gen (shape-generator)
          :decode/json {:leave decode-shape}}
    [:fn shape?]
    schema:shape-attrs]))

(def check-shape-generic-attrs
  (sm/check-fn schema:shape-generic-attrs))

(def check-shape-attrs
  (sm/check-fn schema:shape-attrs))

(def check-shape
  (sm/check-fn schema:shape
               :hint "expected valid shape"))

(def valid-shape?
  (sm/lazy-validator schema:shape))

(def explain-shape
  (sm/lazy-explainer schema:shape))

(defn has-images?
  [{:keys [fills strokes]}]
  (or (some :fill-image fills)
      (some :stroke-image strokes)))

;; Valid attributes

(def ^:private allowed-shape-attrs #{:page-id :component-id :component-file :component-root :main-instance
                                     :remote-synced :shape-ref :touched :blocked :collapsed :locked
                                     :hidden :masked-group :fills :proportion :proportion-lock :constraints-h
                                     :constraints-v :fixed-scroll :r1 :r2 :r3 :r4 :opacity :grids :exports
                                     :strokes :blend-mode :interactions :shadow :blur :grow-type :applied-tokens
                                     :plugin-data})
(def ^:private allowed-shape-geom-attrs #{:x :y :width :height})
(def ^:private allowed-shape-base-attrs #{:id :name :type :selrect :points :transform :transform-inverse :parent-id :frame-id})
(def ^:private allowed-bool-attrs #{:shapes :bool-type :content})
(def ^:private allowed-group-attrs #{:shapes})
(def ^:private allowed-frame-attrs #{:shapes :hide-fill-on-export :show-content :hide-in-viewer})
(def ^:private allowed-image-attrs #{:metadata})
(def ^:private allowed-svg-attrs #{:content})
(def ^:private allowed-path-attrs #{:content})
(def ^:private allowed-text-attrs #{:content})
(def ^:private allowed-generic-attrs (set/union allowed-shape-attrs allowed-shape-geom-attrs allowed-shape-base-attrs))

(defn is-allowed-attr?
  [attr type]
  (case type
    :group   (or (contains? allowed-group-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :frame   (or (contains? allowed-frame-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :bool    (or (contains? allowed-bool-attrs attr)
                 (contains? allowed-shape-attrs attr)
                 (contains? allowed-shape-base-attrs attr))
    :rect    (contains? allowed-generic-attrs attr)
    :circle  (contains? allowed-generic-attrs attr)
    :image   (or (contains? allowed-image-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :svg-raw (or (contains? allowed-svg-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :path    (or (contains? allowed-path-attrs attr)
                 (contains? allowed-shape-attrs attr)
                 (contains? allowed-shape-base-attrs attr))
    :text    (or (contains? allowed-text-attrs attr)
                 (contains? allowed-generic-attrs attr))))




;; --- Initialization

(def ^:private minimal-rect-attrs
  {:type :rect
   :name "Rectangle"
   :fills [{:fill-color default-color
            :fill-opacity 1}]
   :strokes []
   :r1 0
   :r2 0
   :r3 0
   :r4 0})

(def ^:private minimal-image-attrs
  {:type :image
   :r1 0
   :r2 0
   :r3 0
   :r4 0
   :fills []
   :strokes []})

(def ^:private minimal-frame-attrs
  {:frame-id uuid/zero
   :fills [{:fill-color clr/white
            :fill-opacity 1}]
   :strokes []
   :name "Board"
   :shapes []
   :r1 0
   :r2 0
   :r3 0
   :r4 0
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
        attrs (get-minimal-shape type)
        attrs (cond-> attrs
                (and (not= :path type)
                     (not= :bool type))
                (-> (assoc :x 0)
                    (assoc :y 0)
                    (assoc :width 0.01)
                    (assoc :height 0.01)))
        attrs  (-> attrs
                   (assoc :id (uuid/next))
                   (assoc :frame-id uuid/zero)
                   (assoc :parent-id uuid/zero)
                   (assoc :rotation 0))]

    (create-shape attrs)))

(defn setup-rect
  "Initializes the selrect and points for a shape."
  [{:keys [selrect points transform] :as shape}]
  (let [selrect   (or selrect (gsh/shape->rect shape))
        center    (grc/rect->center selrect)
        transform (or transform (gmt/matrix))
        points    (or points
                      (->  selrect
                           (grc/rect->points)
                           (gsh/transform-points center transform)))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))

(defn setup-path
  [{:keys [content selrect points] :as shape}]
  (let [selrect (or selrect
                    (path.segment/content->selrect content)
                    (grc/make-rect))
        points  (or points  (grc/rect->points selrect))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))

(defn- setup-image
  [{:keys [metadata] :as shape}]
  (-> shape
      (assoc :proportion (float (/ (:width metadata)
                                   (:height metadata))))
      (assoc :proportion-lock true)))

(defn setup-shape
  "A function that initializes the geometric data of the shape. The props must
  contain at least :x :y :width :height."
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
  :rfn create-shape})

#?(:clj
   (fres/add-handlers!
    {:name "penpot/shape"
     :class Shape
     :wfn fres/write-map-like
     :rfn (comp map->Shape fres/read-map-like)}))

;; --- SHAPE COPY/PASTE PROPS

;; Copy/paste properties:
;;  - Fill
;;  - Stroke
;;  - Opacity
;;  - Layout (Grid & Flex)
;;  - Flex element
;;  - Flex board
;;  - Text properties
;;  - Contraints
;;  - Shadow
;;  - Blur
;;  - Border radius
(def ^:private basic-extract-props
  #{:fills
    :strokes
    :opacity

    ;; Layout Item
    :layout-item-margin
    :layout-item-margin-type
    :layout-item-h-sizing
    :layout-item-v-sizing
    :layout-item-max-h
    :layout-item-min-h
    :layout-item-max-w
    :layout-item-min-w
    :layout-item-absolute
    :layout-item-z-index

    ;; Constraints
    :constraints-h
    :constraints-v

    :shadow
    :blur

    ;; Radius
    :r1
    :r2
    :r3
    :r4})

(defn extract-props
  "Retrieves an object with the 'pasteable' properties for a shape."
  [shape]
  (letfn [(assoc-props
            [props node attrs]
            (->> attrs
                 (reduce
                  (fn [props attr]
                    (cond-> props
                      (and (not (contains? props attr))
                           (some? (get node attr)))
                      (assoc attr (get node attr))))
                  props)))

          (extract-text-props
            [props shape]
            (->> (txt/node-seq (:content shape))
                 (reduce
                  (fn [result node]
                    (cond-> result
                      (txt/is-root-node? node)
                      (assoc-props node txt/root-attrs)

                      (txt/is-paragraph-node? node)
                      (assoc-props node txt/paragraph-attrs)

                      (txt/is-text-node? node)
                      (assoc-props node txt/text-node-attrs)))
                  props)))

          (extract-layout-attrs [props shape]
            (d/patch-object props (select-keys shape ctsl/layout-attrs)))]

    (let [;; For texts we don't extract the fill
          extract-props
          (cond-> basic-extract-props (cfh/text-shape? shape) (disj :fills))]
      (-> shape
          (select-keys extract-props)
          (cond-> (cfh/text-shape? shape) (extract-text-props shape))
          (cond-> (ctsl/any-layout? shape) (extract-layout-attrs shape))))))

(defn patch-props
  "Given the object of `extract-props` applies it to a shape. Adapt the shape if necesary"
  [shape props objects]

  (letfn [(patch-text-props [shape props]
            (-> shape
                (update
                 :content
                 (fn [content]
                   (->> content
                        (txt/transform-nodes
                         (fn [node]
                           (cond-> node
                             (txt/is-root-node? node)
                             (d/patch-object (select-keys props txt/root-attrs))

                             (txt/is-paragraph-node? node)
                             (d/patch-object (select-keys props txt/paragraph-attrs))

                             (txt/is-text-node? node)
                             (d/patch-object (select-keys props txt/text-node-attrs))))))))))

          (patch-layout-props [shape props]
            (let [shape (d/patch-object shape (select-keys props ctsl/layout-attrs))]
              (cond-> shape
                (ctsl/grid-layout? shape)
                (ctsl/assign-cells objects))))]

    (-> shape
        (d/patch-object (select-keys props basic-extract-props))
        (cond-> (cfh/text-shape? shape) (patch-text-props props))
        (cond-> (cfh/frame-shape? shape) (patch-layout-props props)))))
