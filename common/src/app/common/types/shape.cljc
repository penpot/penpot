;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.common :refer [default-color]]
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.shape.blur :as ctsb]
   [app.common.types.shape.export :as ctse]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.path :as ctsp]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.shape.shadow :as ctss]
   [app.common.types.shape.text :as ctsx]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.test.check.generators :as tgen]))

;; --- Specs

(s/def ::frame-id uuid?)
(s/def ::id uuid?)
(s/def ::name ::us/string)
(s/def ::path (s/nilable ::us/string))
(s/def ::page-id uuid?)
(s/def ::parent-id uuid?)
(s/def ::string ::us/string)
(s/def ::type #{:frame :text :rect :path :image :circle :group :bool :svg-raw})
(s/def ::uuid uuid?)

(s/def ::component-id uuid?)
(s/def ::component-file uuid?)
(s/def ::component-root? boolean?)
(s/def ::shape-ref uuid?)

;; Size constraints

(s/def ::constraints-h #{:left :right :leftright :center :scale})
(s/def ::constraints-v #{:top :bottom :topbottom :center :scale})
(s/def ::fixed-scroll boolean?)

;; Page Data related
(s/def ::blocked boolean?)
(s/def ::collapsed boolean?)

(s/def ::fill-color ::us/rgb-color-str)
(s/def ::fill-opacity ::us/safe-number)
(s/def ::fill-color-gradient (s/nilable ::ctc/gradient))
(s/def ::fill-color-ref-file (s/nilable uuid?))
(s/def ::fill-color-ref-id (s/nilable uuid?))

(s/def ::hide-fill-on-export boolean?)
(s/def ::show-content boolean?)
(s/def ::hide-in-viewer boolean?)

(s/def ::file-thumbnail boolean?)
(s/def ::masked-group? boolean?)
(s/def ::font-family ::us/string)
(s/def ::font-size ::us/safe-integer)
(s/def ::font-style ::us/string)
(s/def ::font-weight ::us/string)
(s/def ::hidden boolean?)
(s/def ::letter-spacing ::us/safe-number)
(s/def ::line-height ::us/safe-number)
(s/def ::locked boolean?)
(s/def ::page-id uuid?)
(s/def ::proportion ::us/safe-number)
(s/def ::proportion-lock boolean?)
(s/def ::stroke-color ::us/string)
(s/def ::stroke-color-gradient (s/nilable ::ctc/gradient))
(s/def ::stroke-color-ref-file (s/nilable uuid?))
(s/def ::stroke-color-ref-id (s/nilable uuid?))
(s/def ::stroke-opacity ::us/safe-number)
(s/def ::stroke-style #{:solid :dotted :dashed :mixed :none :svg})

(def stroke-caps-line #{:round :square})
(def stroke-caps-marker #{:line-arrow :triangle-arrow :square-marker :circle-marker :diamond-marker})
(def stroke-caps (set/union stroke-caps-line stroke-caps-marker))

(s/def ::stroke-cap-start stroke-caps)
(s/def ::stroke-cap-end stroke-caps)

(s/def ::stroke-width ::us/safe-number)
(s/def ::stroke-alignment #{:center :inner :outer})
(s/def ::text-align #{"left" "right" "center" "justify"})
(s/def ::x ::us/safe-number)
(s/def ::y ::us/safe-number)
(s/def ::cx ::us/safe-number)
(s/def ::cy ::us/safe-number)
(s/def ::width ::us/safe-number)
(s/def ::height ::us/safe-number)
(s/def ::index integer?)

(s/def ::x1 ::us/safe-number)
(s/def ::y1 ::us/safe-number)
(s/def ::x2 ::us/safe-number)
(s/def ::y2 ::us/safe-number)

(s/def ::selrect
  (s/keys :req-un [::x ::y ::x1 ::y1 ::x2 ::y2 ::width ::height]))

(s/def ::exports
  (s/coll-of ::ctse/export :kind vector?))

(s/def ::points
  (s/every ::gpt/point :kind vector?))

(s/def ::shapes
  (s/every uuid? :kind vector?))

(s/def ::fill
  (s/and (s/keys :opt-un [::fill-color
                          ::fill-opacity
                          ::fill-color-gradient
                          ::fill-color-ref-file
                          ::fill-color-ref-id])
         (comp boolean seq)))

(s/def ::fills
  (s/coll-of ::fill :kind vector?))

(s/def ::stroke
  (s/keys :opt-un [::stroke-color
                   ::stroke-color-ref-file
                   ::stroke-color-ref-id
                   ::stroke-opacity
                   ::stroke-style
                   ::stroke-width
                   ::stroke-alignment
                   ::stroke-cap-start
                   ::stroke-cap-end]))

(s/def ::strokes
  (s/coll-of ::stroke :kind vector?))

(s/def ::transform ::gmt/matrix)
(s/def ::transform-inverse ::gmt/matrix)
(s/def ::opacity ::us/safe-number)
(s/def ::blend-mode
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

(s/def ::shape-base-attrs
  (s/keys :opt-un [::id
                   ::name
                   ::component-id
                   ::component-file
                   ::component-root?
                   ::shape-ref
                   ::selrect
                   ::points
                   ::blocked
                   ::collapsed
                   ::fills
                   ::hide-fill-on-export
                   ::font-family
                   ::font-size
                   ::font-style
                   ::font-weight
                   ::hidden
                   ::letter-spacing
                   ::line-height
                   ::locked
                   ::proportion
                   ::proportion-lock
                   ::constraints-h
                   ::constraints-v
                   ::fixed-scroll
                   ::ctsr/rx
                   ::ctsr/ry
                   ::ctsr/r1
                   ::ctsr/r2
                   ::ctsr/r3
                   ::ctsr/r4
                   ::x
                   ::y
                   ::exports
                   ::shapes
                   ::strokes
                   ::text-align
                   ::transform
                   ::transform-inverse
                   ::width
                   ::height
                   ::masked-group?
                   ::ctsi/interactions
                   ::ctss/shadow
                   ::ctsb/blur
                   ::opacity
                   ::blend-mode]))

(s/def ::shape-attrs
  (s/with-gen
    (s/merge
     ::shape-base-attrs
     ::ctsl/layout-container-props
     ::ctsl/layout-child-props

     ;; For BACKWARD COMPATIBILITY we need to spec fill and stroke
     ;; attrs as shape toplevel attrs
     ::fill
     ::stroke)
    #(tgen/let [attrs1 (s/gen ::shape-base-attrs)
                attrs2 (s/gen ::ctsl/layout-container-props)
                attrs3 (s/gen ::ctsl/layout-child-props)]
       (merge attrs1 attrs2 attrs3))))

(defmulti shape-spec :type)

(defmethod shape-spec :default [_]
  (s/spec ::shape-attrs))

(defmethod shape-spec :text [_]
  (s/merge ::shape-attrs
           (s/keys :opt-un [::ctsx/content
                            ::ctsx/position-data])))

(defmethod shape-spec :path [_]
  (s/merge ::shape-attrs
           (s/keys :opt-un [::ctsp/content])))

(defmethod shape-spec :frame [_]
  (s/merge ::shape-attrs
           (s/keys :opt-un [::file-thumbnail
                            ::hide-fill-on-export
                            ::show-content
                            ::hide-in-viewer])))

(s/def ::shape
  (s/with-gen
    (s/merge
     (s/keys :req-un [::type ::name])
     (s/multi-spec shape-spec :type))
    (fn []
      (tgen/let [type  (s/gen ::type)
                 name  (s/gen ::name)
                 attrs (s/gen ::shape-attrs)]
        (assoc attrs :type type :name name)))))

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
    :stroke-style :none
    :stroke-alignment :center
    :stroke-width 0
    :stroke-color clr/black
    :stroke-opacity 0
    :rx 0
    :ry 0}

   {:type :text
    :name "Text"
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
  [frame-id rect group-name]
  {:id (uuid/next)
   :type :group
   :name group-name
   :shapes []
   :frame-id frame-id
   :x (:x rect)
   :y (:y rect)
   :width (:width rect)
   :height (:height rect)})

(defn setup-rect-selrect
  "Initializes the selrect and points for a shape."
  [shape]
  (let [selrect (gsh/rect->selrect shape)
        points  (gsh/rect->points shape)
        points  (cond-> points
                  (:transform shape)
                  (gsh/transform-points (gsh/center-points points) (:transform shape)))]
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
  [shape props]
  (let [metadata (or (:metadata shape) (:metadata props))]
    (-> (setup-rect shape props)
        (assoc
          :metadata metadata
          :proportion (/ (:width metadata)
                         (:height metadata))
          :proportion-lock true))))

(defn setup-shape
  "A function that initializes the geometric data of
  the shape. The props must have :x :y :width :height."
  ([props]
   (setup-shape {:type :rect} props))

  ([shape props]
   (case (:type shape)
     :image (setup-image shape props)
     (setup-rect shape props))))

(defn make-shape
  "Make a non group shape, ready to use."
  [type geom-props attrs]
  (-> (if-not (= type :group)
        (make-minimal-shape type)
        (make-minimal-group uuid/zero geom-props (:name attrs)))
      (setup-shape geom-props)
      (merge attrs)))

