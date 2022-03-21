;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.shape
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.spec :as us]
   [app.common.spec.blur :as blur]
   [app.common.spec.color :as color]
   [app.common.spec.export :as export]
   [app.common.spec.interactions :as cti]
   [app.common.spec.radius :as radius]
   [app.common.spec.shadow :as shadow]
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
(s/def ::fill-color-gradient (s/nilable ::color/gradient))
(s/def ::fill-color-ref-file (s/nilable uuid?))
(s/def ::fill-color-ref-id (s/nilable uuid?))

(s/def ::hide-fill-on-export boolean?)

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
(s/def ::stroke-color-gradient (s/nilable ::color/gradient))
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
  (s/coll-of ::export/export :kind vector?))

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
                   ::fill-color
                   ::fill-opacity
                   ::fill-color-gradient
                   ::fill-color-ref-file
                   ::fill-color-ref-id
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
                   ::radius/rx
                   ::radius/ry
                   ::radius/r1
                   ::radius/r2
                   ::radius/r3
                   ::radius/r4
                   ::x
                   ::y
                   ::exports
                   ::shapes
                   ::strokes
                   ::stroke-color
                   ::stroke-color-ref-file
                   ::stroke-color-ref-id
                   ::stroke-opacity
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
                   ::cti/interactions
                   ::shadow/shadow
                   ::blur/blur
                   ::opacity
                   ::blend-mode]))

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
                   :internal.shape.text.position-data/text-transform]
          ))

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
                          ::hide-fill-on-export])))

(s/def ::shape
  (s/and (s/multi-spec shape-spec :type)
         #(contains? % :type)
         #(contains? % :name)))
