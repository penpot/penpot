;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

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
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.shape.shadow :as ctss]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]))

;; --- Specs

(s/def ::frame-id uuid?)
(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::path (s/nilable string?))
(s/def ::page-id uuid?)
(s/def ::parent-id uuid?)
(s/def ::string string?)
(s/def ::type keyword?)
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

(s/def ::fill-color string?)
(s/def ::fill-opacity ::us/safe-number)
(s/def ::fill-color-gradient (s/nilable ::ctc/gradient))
(s/def ::fill-color-ref-file (s/nilable uuid?))
(s/def ::fill-color-ref-id (s/nilable uuid?))

(s/def ::hide-fill-on-export boolean?)
(s/def ::show-content boolean?)
(s/def ::hide-in-viewer boolean?)

(s/def ::file-thumbnail boolean?)
(s/def ::masked-group? boolean?)
(s/def ::font-family string?)
(s/def ::font-size ::us/safe-integer)
(s/def ::font-style string?)
(s/def ::font-weight string?)
(s/def ::hidden boolean?)
(s/def ::letter-spacing ::us/safe-number)
(s/def ::line-height ::us/safe-number)
(s/def ::locked boolean?)
(s/def ::page-id uuid?)
(s/def ::proportion ::us/safe-number)
(s/def ::proportion-lock boolean?)
(s/def ::stroke-color string?)
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
  (s/keys :opt-un [::fill-color
                   ::fill-opacity
                   ::fill-color-gradient
                   ::fill-color-ref-file
                   ::fill-color-ref-id]))

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

(s/def ::shape-attrs
  (s/and
   ::ctsl/layout-container-props
   ::ctsl/layout-child-props
   (s/keys :opt-un [::id
                    ::type
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
                    ::fill-color         ;; TODO: remove these attributes
                    ::fill-opacity       ;;       when backward compatibility
                    ::fill-color-gradient ;;       is no longer needed
                    ::fill-color-ref-file ;;
                    ::fill-color-ref-id   ;;
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
                    ::stroke-color         ;; TODO: same thing
                    ::stroke-color-ref-file ;;
                    ::stroke-color-ref-i    ;;
                    ::stroke-opacity        ;;
                    ::stroke-style
                    ::stroke-width
                    ::stroke-alignment
                    ::stroke-cap-start
                    ::stroke-cap-end
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
                    ::blend-mode])))

(s/def :internal.shape.text/type #{"root" "paragraph-set" "paragraph"})
(s/def :internal.shape.text/children
  (s/coll-of :internal.shape.text/content
             :kind vector?
             :min-count 1))

(s/def :internal.shape.text/text string?)
(s/def :internal.shape.text/key string?)

(s/def :internal.shape.text/content
  (s/nilable
   (s/or :text-container
         (s/keys :req-un [:internal.shape.text/type]
                 :opt-un [:internal.shape.text/key
                          :internal.shape.text/children])
         :text-content
         (s/keys :req-un [:internal.shape.text/text]))))

(s/def :internal.shape.text/position-data
  (s/coll-of :internal.shape.text/position-data-element
             :kind vector?
             :min-count 1))

(s/def :internal.shape.text/position-data-element
  (s/keys :req-un [:internal.shape.text.position-data/x
                   :internal.shape.text.position-data/y
                   :internal.shape.text.position-data/width
                   :internal.shape.text.position-data/height]
          :opt-un [:internal.shape.text.position-data/fill-color
                   :internal.shape.text.position-data/fill-opacity
                   :internal.shape.text.position-data/font-family
                   :internal.shape.text.position-data/font-size
                   :internal.shape.text.position-data/font-style
                   :internal.shape.text.position-data/font-weight
                   :internal.shape.text.position-data/rtl
                   :internal.shape.text.position-data/text
                   :internal.shape.text.position-data/text-decoration
                   :internal.shape.text.position-data/text-transform]))

(s/def :internal.shape.text.position-data/x ::us/safe-number)
(s/def :internal.shape.text.position-data/y ::us/safe-number)
(s/def :internal.shape.text.position-data/width ::us/safe-number)
(s/def :internal.shape.text.position-data/height ::us/safe-number)

(s/def :internal.shape.text.position-data/fill-color ::fill-color)
(s/def :internal.shape.text.position-data/fill-opacity ::fill-opacity)
(s/def :internal.shape.text.position-data/fill-color-gradient ::fill-color-gradient)

(s/def :internal.shape.text.position-data/font-family string?)
(s/def :internal.shape.text.position-data/font-size string?)
(s/def :internal.shape.text.position-data/font-style string?)
(s/def :internal.shape.text.position-data/font-weight string?)
(s/def :internal.shape.text.position-data/rtl boolean?)
(s/def :internal.shape.text.position-data/text string?)
(s/def :internal.shape.text.position-data/text-decoration string?)
(s/def :internal.shape.text.position-data/text-transform string?)

(s/def :internal.shape.path/command keyword?)
(s/def :internal.shape.path/params
  (s/nilable (s/map-of keyword? any?)))

(s/def :internal.shape.path/command-item
  (s/keys :req-un [:internal.shape.path/command]
          :opt-un [:internal.shape.path/params]))

(s/def :internal.shape.path/content
  (s/coll-of :internal.shape.path/command-item :kind vector?))

(defmulti shape-spec :type)

(defmethod shape-spec :default [_]
  (s/spec ::shape-attrs))

(defmethod shape-spec :text [_]
  (s/and ::shape-attrs
         (s/keys :opt-un [:internal.shape.text/content
                          :internal.shape.text/position-data])))

(defmethod shape-spec :path [_]
  (s/and ::shape-attrs
         (s/keys :opt-un [:internal.shape.path/content])))

(defmethod shape-spec :frame [_]
  (s/and ::shape-attrs
         (s/keys :opt-un [::file-thumbnail
                          ::hide-fill-on-export
                          ::show-content
                          ::hide-in-viewer])))

(s/def ::shape
  (s/and (s/multi-spec shape-spec :type)
         #(contains? % :type)
         #(contains? % :name)))


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
    :name "Board-1"
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
  (-> (make-minimal-shape type)
      (setup-shape geom-props)
      (merge attrs)))

