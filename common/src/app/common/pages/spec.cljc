;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.spec
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.spec :as us]
   [app.common.types.interactions :as cti]
   [app.common.types.page-options :as cto]
   [app.common.types.radius :as ctr]
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

(s/def :internal.matrix/a ::us/safe-number)
(s/def :internal.matrix/b ::us/safe-number)
(s/def :internal.matrix/c ::us/safe-number)
(s/def :internal.matrix/d ::us/safe-number)
(s/def :internal.matrix/e ::us/safe-number)
(s/def :internal.matrix/f ::us/safe-number)

(s/def ::matrix
  (s/and (s/keys :req-un [:internal.matrix/a
                          :internal.matrix/b
                          :internal.matrix/c
                          :internal.matrix/d
                          :internal.matrix/e
                          :internal.matrix/f])
         gmt/matrix?))


(s/def :internal.point/x ::us/safe-number)
(s/def :internal.point/y ::us/safe-number)

(s/def ::point
  (s/and (s/keys :req-un [:internal.point/x
                          :internal.point/y])
         gpt/point?))

;; GRADIENTS

(s/def :internal.gradient.stop/color ::string)
(s/def :internal.gradient.stop/opacity ::us/safe-number)
(s/def :internal.gradient.stop/offset ::us/safe-number)

(s/def :internal.gradient/type #{:linear :radial})
(s/def :internal.gradient/start-x ::us/safe-number)
(s/def :internal.gradient/start-y ::us/safe-number)
(s/def :internal.gradient/end-x ::us/safe-number)
(s/def :internal.gradient/end-y ::us/safe-number)
(s/def :internal.gradient/width ::us/safe-number)

(s/def :internal.gradient/stop
  (s/keys :req-un [:internal.gradient.stop/color
                   :internal.gradient.stop/opacity
                   :internal.gradient.stop/offset]))

(s/def :internal.gradient/stops
  (s/coll-of :internal.gradient/stop :kind vector?))

(s/def ::gradient
  (s/keys :req-un [:internal.gradient/type
                   :internal.gradient/start-x
                   :internal.gradient/start-y
                   :internal.gradient/end-x
                   :internal.gradient/end-y
                   :internal.gradient/width
                   :internal.gradient/stops]))


;;; COLORS

(s/def :internal.color/name ::string)
(s/def :internal.color/path (s/nilable ::string))
(s/def :internal.color/value (s/nilable ::string))
(s/def :internal.color/color (s/nilable ::string))
(s/def :internal.color/opacity (s/nilable ::us/safe-number))
(s/def :internal.color/gradient (s/nilable ::gradient))

(s/def ::color
  (s/keys :opt-un [::id
                   :internal.color/name
                   :internal.color/path
                   :internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))


;;; SHADOW EFFECT

(s/def :internal.shadow/id uuid?)
(s/def :internal.shadow/style #{:drop-shadow :inner-shadow})
(s/def :internal.shadow/color ::color)
(s/def :internal.shadow/offset-x ::us/safe-number)
(s/def :internal.shadow/offset-y ::us/safe-number)
(s/def :internal.shadow/blur ::us/safe-number)
(s/def :internal.shadow/spread ::us/safe-number)
(s/def :internal.shadow/hidden boolean?)

(s/def :internal.shadow/shadow
  (s/keys :req-un [:internal.shadow/id
                   :internal.shadow/style
                   :internal.shadow/color
                   :internal.shadow/offset-x
                   :internal.shadow/offset-y
                   :internal.shadow/blur
                   :internal.shadow/spread
                   :internal.shadow/hidden]))

(s/def ::shadow
  (s/coll-of :internal.shadow/shadow :kind vector?))


;;; BLUR EFFECT

(s/def :internal.blur/id uuid?)
(s/def :internal.blur/type #{:layer-blur})
(s/def :internal.blur/value ::us/safe-number)
(s/def :internal.blur/hidden boolean?)

(s/def ::blur
  (s/keys :req-un [:internal.blur/id
                   :internal.blur/type
                   :internal.blur/value
                   :internal.blur/hidden]))

;; Size constraints

(s/def :internal.shape/constraints-h #{:left :right :leftright :center :scale})
(s/def :internal.shape/constraints-v #{:top :bottom :topbottom :center :scale})
(s/def :internal.shape/fixed-scroll boolean?)

; Shapes in the top frame have no constraints. Shapes directly below some
; frame are left-top constrained. Else (shapes in a group) are scaled.
(defn default-constraints-h
  [shape]
  (if (= (:parent-id shape) uuid/zero)
    nil
    (if (= (:parent-id shape) (:frame-id shape))
      :left
      :scale)))

(defn default-constraints-v
  [shape]
  (if (= (:parent-id shape) uuid/zero)
    nil
    (if (= (:parent-id shape) (:frame-id shape))
      :top
      :scale)))

;; Page Data related
(s/def :internal.shape/blocked boolean?)
(s/def :internal.shape/collapsed boolean?)
(s/def :internal.shape/content any?)

(s/def :internal.shape/fill-color string?)
(s/def :internal.shape/fill-opacity ::us/safe-number)
(s/def :internal.shape/fill-color-gradient (s/nilable ::gradient))
(s/def :internal.shape/fill-color-ref-file (s/nilable uuid?))
(s/def :internal.shape/fill-color-ref-id (s/nilable uuid?))

(s/def :internal.shape/font-family string?)
(s/def :internal.shape/font-size ::us/safe-integer)
(s/def :internal.shape/font-style string?)
(s/def :internal.shape/font-weight string?)
(s/def :internal.shape/hidden boolean?)
(s/def :internal.shape/letter-spacing ::us/safe-number)
(s/def :internal.shape/line-height ::us/safe-number)
(s/def :internal.shape/locked boolean?)
(s/def :internal.shape/page-id uuid?)
(s/def :internal.shape/proportion ::us/safe-number)
(s/def :internal.shape/proportion-lock boolean?)
(s/def :internal.shape/stroke-color string?)
(s/def :internal.shape/stroke-color-gradient (s/nilable ::gradient))
(s/def :internal.shape/stroke-color-ref-file (s/nilable uuid?))
(s/def :internal.shape/stroke-color-ref-id (s/nilable uuid?))
(s/def :internal.shape/stroke-opacity ::us/safe-number)
(s/def :internal.shape/stroke-style #{:solid :dotted :dashed :mixed :none :svg})

(def stroke-caps-line #{:round :square})
(def stroke-caps-marker #{:line-arrow :triangle-arrow :square-marker :circle-marker :diamond-marker})
(def stroke-caps (set/union stroke-caps-line stroke-caps-marker))
(s/def :internal.shape/stroke-cap-start stroke-caps)
(s/def :internal.shape/stroke-cap-end stroke-caps)

(defn has-caps?
  [shape]
  (= (:type shape) :path))

(s/def :internal.shape/stroke-width ::us/safe-number)
(s/def :internal.shape/stroke-alignment #{:center :inner :outer})
(s/def :internal.shape/text-align #{"left" "right" "center" "justify"})
(s/def :internal.shape/x ::us/safe-number)
(s/def :internal.shape/y ::us/safe-number)
(s/def :internal.shape/cx ::us/safe-number)
(s/def :internal.shape/cy ::us/safe-number)
(s/def :internal.shape/width ::us/safe-number)
(s/def :internal.shape/height ::us/safe-number)
(s/def :internal.shape/index integer?)
(s/def :internal.shape/shadow ::shadow)
(s/def :internal.shape/blur ::blur)

(s/def :internal.shape/x1 ::us/safe-number)
(s/def :internal.shape/y1 ::us/safe-number)
(s/def :internal.shape/x2 ::us/safe-number)
(s/def :internal.shape/y2 ::us/safe-number)

(s/def :internal.shape.export/suffix string?)
(s/def :internal.shape.export/scale ::us/safe-number)
(s/def :internal.shape/export
  (s/keys :req-un [::type
                   :internal.shape.export/suffix
                   :internal.shape.export/scale]))

(s/def :internal.shape/exports
  (s/coll-of :internal.shape/export :kind vector?))

(s/def :internal.shape/selrect
  (s/keys :req-un [:internal.shape/x
                   :internal.shape/y
                   :internal.shape/x1
                   :internal.shape/y1
                   :internal.shape/x2
                   :internal.shape/y2
                   :internal.shape/width
                   :internal.shape/height]))

(s/def :internal.shape/points
  (s/every ::point :kind vector?))

(s/def :internal.shape/shapes
  (s/every uuid? :kind vector?))

(s/def :internal.shape/transform ::matrix)
(s/def :internal.shape/transform-inverse ::matrix)

(s/def :internal.shape/opacity ::us/safe-number)
(s/def :internal.shape/blend-mode
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
  (s/keys :opt-un [:internal.shape/selrect
                   :internal.shape/points
                   :internal.shape/blocked
                   :internal.shape/collapsed
                   :internal.shape/content
                   :internal.shape/fill-color
                   :internal.shape/fill-opacity
                   :internal.shape/fill-color-gradient
                   :internal.shape/fill-color-ref-file
                   :internal.shape/fill-color-ref-id
                   :internal.shape/font-family
                   :internal.shape/font-size
                   :internal.shape/font-style
                   :internal.shape/font-weight
                   :internal.shape/hidden
                   :internal.shape/letter-spacing
                   :internal.shape/line-height
                   :internal.shape/locked
                   :internal.shape/proportion
                   :internal.shape/proportion-lock
                   :internal.shape/constraints-h
                   :internal.shape/constraints-v
                   :internal.shape/fixed-scroll
                   ::ctr/rx
                   ::ctr/ry
                   ::ctr/r1
                   ::ctr/r2
                   ::ctr/r3
                   ::ctr/r4
                   :internal.shape/x
                   :internal.shape/y
                   :internal.shape/exports
                   :internal.shape/shapes
                   :internal.shape/stroke-color
                   :internal.shape/stroke-color-ref-file
                   :internal.shape/stroke-color-ref-id
                   :internal.shape/stroke-opacity
                   :internal.shape/stroke-style
                   :internal.shape/stroke-width
                   :internal.shape/stroke-alignment
                   :internal.shape/stroke-cap-start
                   :internal.shape/stroke-cap-end
                   :internal.shape/text-align
                   :internal.shape/transform
                   :internal.shape/transform-inverse
                   :internal.shape/width
                   :internal.shape/height
                   ::cti/interactions
                   :internal.shape/masked-group?
                   :internal.shape/shadow
                   :internal.shape/blur
                   :internal.shape/opacity
                   :internal.shape/blend-mode]))


;; shapes-group is handled differently

(s/def ::minimal-shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def ::shape
  (s/and ::minimal-shape
         ::shape-attrs
         (s/keys :opt-un [::id
                          ::component-id
                          ::component-file
                          ::component-root?
                          ::shape-ref])))

(s/def :internal.page/objects (s/map-of uuid? ::shape))

(s/def ::page
  (s/keys :req-un [::id
                   ::name
                   ::cto/options
                   :internal.page/objects]))


(s/def ::recent-color
  (s/keys :opt-un [:internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))

(s/def :internal.media-object/name ::string)
(s/def :internal.media-object/width ::us/safe-integer)
(s/def :internal.media-object/height ::us/safe-integer)
(s/def :internal.media-object/mtype ::string)

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   :internal.media-object/width
                   :internal.media-object/height
                   :internal.media-object/mtype]))

(s/def ::media-object-update
  (s/keys :req-un [::id]
          :opt-un [::name
                   :internal.media-object/width
                   :internal.media-object/height
                   :internal.media-object/mtype]))

(s/def :internal.file/colors
  (s/map-of ::uuid ::color))

(s/def :internal.file/recent-colors
  (s/coll-of ::recent-color :kind vector?))

(s/def :internal.typography/id ::id)
(s/def :internal.typography/name ::string)
(s/def :internal.typography/path (s/nilable ::string))
(s/def :internal.typography/font-id ::string)
(s/def :internal.typography/font-family ::string)
(s/def :internal.typography/font-variant-id ::string)
(s/def :internal.typography/font-size ::string)
(s/def :internal.typography/font-weight ::string)
(s/def :internal.typography/font-style ::string)
(s/def :internal.typography/line-height ::string)
(s/def :internal.typography/letter-spacing ::string)
(s/def :internal.typography/text-transform ::string)

(s/def ::typography
  (s/keys :req-un [:internal.typography/id
                   :internal.typography/name
                   :internal.typography/font-id
                   :internal.typography/font-family
                   :internal.typography/font-variant-id
                   :internal.typography/font-size
                   :internal.typography/font-weight
                   :internal.typography/font-style
                   :internal.typography/line-height
                   :internal.typography/letter-spacing
                   :internal.typography/text-transform]
          :opt-un [:internal.typography/path]))

(s/def :internal.file/pages
  (s/coll-of ::uuid :kind vector?))

(s/def :internal.file/media
  (s/map-of ::uuid ::media-object))

(s/def :internal.file/pages-index
  (s/map-of ::uuid ::page))

(s/def ::data
  (s/keys :req-un [:internal.file/pages-index
                   :internal.file/pages]
          :opt-un [:internal.file/colors
                   :internal.file/recent-colors
                   :internal.file/media]))

(s/def :internal.container/type #{:page :component})

(s/def ::container
  (s/keys :req-un [:internal.container/type
                   ::id
                   ::name
                   :internal.page/objects]))

(defmulti operation-spec :type)

(s/def :internal.operations.set/attr keyword?)
(s/def :internal.operations.set/val any?)
(s/def :internal.operations.set/touched
  (s/nilable (s/every keyword? :kind set?)))
(s/def :internal.operations.set/remote-synced?
  (s/nilable boolean?))

(defmethod operation-spec :set [_]
  (s/keys :req-un [:internal.operations.set/attr
                   :internal.operations.set/val]))

(defmethod operation-spec :set-touched [_]
  (s/keys :req-un [:internal.operations.set/touched]))

(defmethod operation-spec :set-remote-synced [_]
  (s/keys :req-un [:internal.operations.set/remote-synced?]))

(defmulti change-spec :type)

(s/def :internal.changes.set-option/option any?)
(s/def :internal.changes.set-option/value any?)

(defmethod change-spec :set-option [_]
  (s/keys :req-un [:internal.changes.set-option/option
                   :internal.changes.set-option/value]))

(s/def :internal.changes.add-obj/obj ::shape)

(defn- valid-container-id-frame?
  [o]
  (or (and (contains? o :page-id)
           (not (contains? o :component-id))
           (some? (:frame-id o)))
      (and (contains? o :component-id)
           (not (contains? o :page-id))
           (nil? (:frame-id o)))))

(defn- valid-container-id?
  [o]
  (or (and (contains? o :page-id)
           (not (contains? o :component-id)))
      (and (contains? o :component-id)
           (not (contains? o :page-id)))))

(defmethod change-spec :add-obj [_]
  (s/and (s/keys :req-un [::id :internal.changes.add-obj/obj]
                 :opt-un [::page-id ::component-id ::parent-id ::frame-id])
         valid-container-id-frame?))

(s/def ::operation (s/multi-spec operation-spec :type))
(s/def ::operations (s/coll-of ::operation))

(defmethod change-spec :mod-obj [_]
  (s/and (s/keys :req-un [::id ::operations]
                 :opt-un [::page-id ::component-id])
         valid-container-id?))

(defmethod change-spec :del-obj [_]
  (s/and (s/keys :req-un [::id]
                 :opt-un [::page-id ::component-id])
         valid-container-id?))

(s/def :internal.changes.reg-objects/shapes
  (s/coll-of uuid? :kind vector?))

(defmethod change-spec :reg-objects [_]
  (s/and (s/keys :req-un [:internal.changes.reg-objects/shapes]
                 :opt-un [::page-id ::component-id])
         valid-container-id?))

(defmethod change-spec :mov-objects [_]
  (s/and (s/keys :req-un [::parent-id :internal.shape/shapes]
                 :opt-un [::page-id ::component-id ::index])
         valid-container-id?))

(defmethod change-spec :add-page [_]
  (s/or :empty (s/keys :req-un [::id ::name])
        :complete (s/keys :req-un [::page])))

(defmethod change-spec :mod-page [_]
  (s/keys :req-un [::id ::name]))

(defmethod change-spec :del-page [_]
  (s/keys :req-un [::id]))

(defmethod change-spec :mov-page [_]
  (s/keys :req-un [::id ::index]))

(defmethod change-spec :add-color [_]
  (s/keys :req-un [::color]))

(defmethod change-spec :mod-color [_]
  (s/keys :req-un [::color]))

(defmethod change-spec :del-color [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.add-recent-color/color ::recent-color)

(defmethod change-spec :add-recent-color [_]
  (s/keys :req-un [:internal.changes.add-recent-color/color]))

(s/def :internal.changes.media/object ::media-object)

(defmethod change-spec :add-media [_]
  (s/keys :req-un [:internal.changes.media/object]))

(s/def :internal.changes.media.mod/object ::media-object-update)

(defmethod change-spec :mod-media [_]
  (s/keys :req-un [:internal.changes.media.mod/object]))

(defmethod change-spec :del-media [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.add-component/shapes
  (s/coll-of ::shape))

(defmethod change-spec :add-component [_]
  (s/keys :req-un [::id ::name :internal.changes.add-component/shapes]
          :opt-un [::path]))

(defmethod change-spec :mod-component [_]
  (s/keys :req-un [::id]
          :opt-un [::name :internal.changes.add-component/shapes]))

(defmethod change-spec :del-component [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.typography/typography ::typography)

(defmethod change-spec :add-typography [_]
  (s/keys :req-un [:internal.changes.typography/typography]))

(defmethod change-spec :mod-typography [_]
  (s/keys :req-un [:internal.changes.typography/typography]))

(defmethod change-spec :del-typography [_]
  (s/keys :req-un [:internal.typography/id]))

(s/def ::change (s/multi-spec change-spec :type))
(s/def ::changes (s/coll-of ::change))
