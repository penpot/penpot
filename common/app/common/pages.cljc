;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]
   [app.common.data :as d]
   [app.common.pages-helpers :as cph]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]))

(def file-version 4)
(def max-safe-int 9007199254740991)
(def min-safe-int -9007199254740991)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Transformation Changes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Specs

(s/def ::frame-id uuid?)
(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::page-id uuid?)
(s/def ::parent-id uuid?)
(s/def ::string string?)
(s/def ::type keyword?)
(s/def ::uuid uuid?)

(s/def ::component-id uuid?)
(s/def ::component-file uuid?)
(s/def ::component-root? boolean?)
(s/def ::shape-ref uuid?)

(s/def ::safe-integer ::us/safe-integer)
(s/def ::safe-number ::us/safe-number)

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
(s/def :internal.gradient.stop/opacity ::safe-number)
(s/def :internal.gradient.stop/offset ::safe-number)

(s/def :internal.gradient/type #{:linear :radial})
(s/def :internal.gradient/start-x ::safe-number)
(s/def :internal.gradient/start-y ::safe-number)
(s/def :internal.gradient/end-x ::safe-number)
(s/def :internal.gradient/end-y ::safe-number)
(s/def :internal.gradient/width ::safe-number)

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
(s/def :internal.color/value (s/nilable ::string))
(s/def :internal.color/color (s/nilable ::string))
(s/def :internal.color/opacity (s/nilable ::safe-number))
(s/def :internal.color/gradient (s/nilable ::gradient))

(s/def ::color
  (s/keys :opt-un [::id
                   :internal.color/name
                   :internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))



;;; SHADOW EFFECT

(s/def :internal.shadow/id uuid?)
(s/def :internal.shadow/style #{:drop-shadow :inner-shadow})
(s/def :internal.shadow/color ::color)
(s/def :internal.shadow/offset-x ::safe-number)
(s/def :internal.shadow/offset-y ::safe-number)
(s/def :internal.shadow/blur ::safe-number)
(s/def :internal.shadow/spread ::safe-number)
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
(s/def :internal.blur/value ::safe-number)
(s/def :internal.blur/hidden boolean?)

(s/def ::blur
  (s/keys :req-un [:internal.blur/id
                   :internal.blur/type
                   :internal.blur/value
                   :internal.blur/hidden]))

;; Page Options
(s/def :internal.page.grid.color/value string?)
(s/def :internal.page.grid.color/opacity ::safe-number)

(s/def :internal.page.grid/size ::safe-integer)
(s/def :internal.page.grid/color
  (s/keys :req-un [:internal.page.grid.color/value
                   :internal.page.grid.color/opacity]))

(s/def :internal.page.grid/type #{:stretch :left :center :right})
(s/def :internal.page.grid/item-length (s/nilable ::safe-integer))
(s/def :internal.page.grid/gutter (s/nilable ::safe-integer))
(s/def :internal.page.grid/margin (s/nilable ::safe-integer))

(s/def :internal.page.grid/square
  (s/keys :req-un [:internal.page.grid/size
                   :internal.page.grid/color]))

(s/def :internal.page.grid/column
  (s/keys :req-un [:internal.page.grid/size
                   :internal.page.grid/color
                   :internal.page.grid/type
                   :internal.page.grid/item-length
                   :internal.page.grid/gutter
                   :internal.page.grid/margin]))

(s/def :internal.page.grid/row :internal.page.grid/column)

(s/def :internal.page.options/background string?)
(s/def :internal.page.options/saved-grids
  (s/keys :req-un [:internal.page.grid/square
                   :internal.page.grid/row
                   :internal.page.grid/column]))

(s/def :internal.page/options
  (s/keys :opt-un [:internal.page.options/background]))

;; Interactions

(s/def :internal.shape.interaction/event-type #{:click}) ; In the future we will have more options
(s/def :internal.shape.interaction/action-type #{:navigate})
(s/def :internal.shape.interaction/destination ::uuid)

(s/def :internal.shape/interaction
  (s/keys :req-un [:internal.shape.interaction/event-type
                   :internal.shape.interaction/action-type
                   :internal.shape.interaction/destination]))

(s/def :internal.shape/interactions
  (s/coll-of :internal.shape/interaction :kind vector?))

;; Page Data related
(s/def :internal.shape/blocked boolean?)
(s/def :internal.shape/collapsed boolean?)
(s/def :internal.shape/content any?)

(s/def :internal.shape/fill-color string?)
(s/def :internal.shape/fill-opacity ::safe-number)
(s/def :internal.shape/fill-color-gradient (s/nilable ::gradient))
(s/def :internal.shape/fill-color-ref-file (s/nilable uuid?))
(s/def :internal.shape/fill-color-ref-id (s/nilable uuid?))

(s/def :internal.shape/font-family string?)
(s/def :internal.shape/font-size ::safe-integer)
(s/def :internal.shape/font-style string?)
(s/def :internal.shape/font-weight string?)
(s/def :internal.shape/hidden boolean?)
(s/def :internal.shape/letter-spacing ::safe-number)
(s/def :internal.shape/line-height ::safe-number)
(s/def :internal.shape/locked boolean?)
(s/def :internal.shape/page-id uuid?)
(s/def :internal.shape/proportion ::safe-number)
(s/def :internal.shape/proportion-lock boolean?)
(s/def :internal.shape/rx ::safe-number)
(s/def :internal.shape/ry ::safe-number)
(s/def :internal.shape/stroke-color string?)
(s/def :internal.shape/stroke-color-gradient (s/nilable ::gradient))
(s/def :internal.shape/stroke-color-ref-file (s/nilable uuid?))
(s/def :internal.shape/stroke-color-ref-id (s/nilable uuid?))
(s/def :internal.shape/stroke-opacity ::safe-number)
(s/def :internal.shape/stroke-style #{:solid :dotted :dashed :mixed :none})
(s/def :internal.shape/stroke-width ::safe-number)
(s/def :internal.shape/stroke-alignment #{:center :inner :outer})
(s/def :internal.shape/text-align #{"left" "right" "center" "justify"})
(s/def :internal.shape/x ::safe-number)
(s/def :internal.shape/y ::safe-number)
(s/def :internal.shape/cx ::safe-number)
(s/def :internal.shape/cy ::safe-number)
(s/def :internal.shape/width ::safe-number)
(s/def :internal.shape/height ::safe-number)
(s/def :internal.shape/index integer?)
(s/def :internal.shape/shadow ::shadow)
(s/def :internal.shape/blur ::blur)

(s/def :internal.shape/x1 ::safe-number)
(s/def :internal.shape/y1 ::safe-number)
(s/def :internal.shape/x2 ::safe-number)
(s/def :internal.shape/y2 ::safe-number)

(s/def :internal.shape.export/suffix string?)
(s/def :internal.shape.export/scale ::safe-number)
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
                   :internal.shape/rx
                   :internal.shape/ry
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
                   :internal.shape/text-align
                   :internal.shape/transform
                   :internal.shape/transform-inverse
                   :internal.shape/width
                   :internal.shape/height
                   :internal.shape/interactions
                   :internal.shape/masked-group?
                   :internal.shape/shadow
                   :internal.shape/blur]))

(def component-sync-attrs {:fill-color            :fill-group
                           :fill-opacity          :fill-group
                           :fill-color-gradient   :fill-group
                           :fill-color-ref-file   :fill-group
                           :fill-color-ref-id     :fill-group
                           :content               :content-group
                           :font-family           :text-font-group
                           :font-size             :text-font-group
                           :font-style            :text-font-group
                           :font-weight           :text-font-group
                           :letter-spacing        :text-display-group
                           :line-height           :text-display-group
                           :text-align            :text-display-group
                           :stroke-color          :stroke-group
                           :stroke-color-gradient :stroke-group
                           :stroke-color-ref-file :stroke-group
                           :stroke-color-ref-id   :stroke-group
                           :stroke-opacity        :stroke-group
                           :stroke-style          :stroke-group
                           :stroke-width          :stroke-group
                           :stroke-alignment      :stroke-group
                           :rx                    :radius-group
                           :ry                    :radius-group
                           :selrect               :geometry-group
                           :points                :geometry-group
                           :locked                :geometry-group
                           :proportion            :geometry-group
                           :proportion-lock       :geometry-group
                           :x                     :geometry-group
                           :y                     :geometry-group
                           :width                 :geometry-group
                           :height                :geometry-group
                           :transform             :geometry-group
                           :transform-inverse     :geometry-group
                           :shadow                :shadow-group
                           :blur                  :blur-group
                           :masked-group?         :mask-group})
                           ;; shapes-group is handled differently

(s/def ::minimal-shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def ::shape
  (s/and ::minimal-shape ::shape-attrs
         (s/keys :opt-un [::id
                          ::component-id
                          ::component-file
                          ::component-root?
                          ::shape-ref])))

(s/def :internal.page/objects (s/map-of uuid? ::shape))

(s/def ::page
  (s/keys :req-un [::id
                   ::name
                   :internal.page/options
                   :internal.page/objects]))


(s/def ::recent-color
  (s/keys :opt-un [:internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))

(s/def :internal.media-object/name ::string)
(s/def :internal.media-object/path ::string)
(s/def :internal.media-object/width ::safe-integer)
(s/def :internal.media-object/height ::safe-integer)
(s/def :internal.media-object/mtype ::string)
(s/def :internal.media-object/thumb-path ::string)
(s/def :internal.media-object/thumb-width ::safe-integer)
(s/def :internal.media-object/thumb-height ::safe-integer)
(s/def :internal.media-object/thumb-mtype ::string)

(s/def ::media-object
  (s/keys :req-un [::id ::name
                   :internal.media-object/name
                   :internal.media-object/path
                   :internal.media-object/width
                   :internal.media-object/height
                   :internal.media-object/mtype
                   :internal.media-object/thumb-path]))


(s/def :internal.file/colors
  (s/map-of ::uuid ::color))

(s/def :internal.file/recent-colors
  (s/coll-of ::recent-color :kind vector?))

(s/def :internal.typography/id ::id)
(s/def :internal.typography/name ::string)
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
                   :internal.typography/text-transform]))

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

(defmethod operation-spec :set [_]
  (s/keys :req-un [:internal.operations.set/attr
                   :internal.operations.set/val]))

(defmethod operation-spec :set-touched [_]
  (s/keys :req-un [:internal.operations.set/touched]))

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

(defmethod change-spec :mod-media [_]
  (s/keys :req-un [:internal.changes.media/object]))

(defmethod change-spec :del-media [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.add-component/shapes
  (s/coll-of ::shape))

(defmethod change-spec :add-component [_]
  (s/keys :req-un [::id ::name :internal.changes.add-component/shapes]))

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

(def default-color "#b1b2b5") ;; $color-gray-20
(def default-shape-attrs
  {:fill-color default-color
   :fill-opacity 1})

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

   {:type :icon}

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
    :content nil}])

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
  ([] (make-file-data (uuid/next)))
  ([id]
   (let [
         pd (assoc empty-page-data
                   :id id
                   :name "Page-1")]
     (-> empty-file-data
         (update :pages conj id)
         (update :pages-index assoc id pd)))))

;; --- Changes Processing Impl

(defmulti process-change (fn [_ change] (:type change)))
(defmulti process-operation (fn [_ op] (:type op)))

(defn process-changes
  [data items]
  (->> (us/verify ::changes items)
       (reduce #(do
                  ;; (prn "process-change" (:type %2) (:id %2))
                  (or (process-change %1 %2) %1))
               data)))

(defmethod process-change :set-option
  [data {:keys [page-id option value]}]
  (d/update-in-when data [:pages-index page-id]
                    (fn [data]
                      (let [path (if (seqable? option) option [option])]
                        (if value
                          (assoc-in data (into [:options] path) value)
                          (assoc data :options (d/dissoc-in (:options data) path)))))))

(defmethod process-change :add-obj
  [data {:keys [id obj page-id component-id frame-id parent-id
                index ignore-touched]}]
  (letfn [(update-fn [data]
            (let [parent-id (or parent-id frame-id)
                  objects   (:objects data)
                  obj (assoc obj
                             :frame-id frame-id
                             :parent-id parent-id
                             :id id)]
              (if (and (or (nil? parent-id) (contains? objects parent-id))
                       (or (nil? frame-id) (contains? objects frame-id)))
                (-> data
                    (update :objects assoc id obj)
                    (update-in [:objects parent-id :shapes]
                               (fn [shapes]
                                 (let [shapes (or shapes [])]
                                   (cond
                                     (some #{id} shapes)
                                     shapes

                                     (nil? index)
                                     (if (= :frame (:type obj))
                                       (d/concat [id] shapes)
                                       (conj shapes id))

                                     :else
                                     (cph/insert-at-index shapes index [id])))))

                    (cond-> (and (:shape-ref (get-in data [:objects parent-id]))
                                 (not= parent-id frame-id)
                                 (not ignore-touched))
                      (update-in [:objects parent-id :touched]
                                 cph/set-touched-group :shapes-group)))
                data)))]
    (if page-id
      (d/update-in-when data [:pages-index page-id] update-fn)
      (d/update-in-when data [:components component-id] update-fn))))

(defmethod process-change :mod-obj
  [data {:keys [id page-id component-id operations]}]
  (let [update-fn (fn [objects]
                    (if-let [obj (get objects id)]
                      (let [result (reduce process-operation obj operations)]
                        (us/verify ::shape result)
                        (assoc objects id result))
                      objects))]
    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] update-fn)
      (d/update-in-when data [:components component-id :objects] update-fn))))

(defmethod process-change :del-obj
  [data {:keys [page-id component-id id ignore-touched]}]
  (letfn [(delete-object [objects id]
            (if-let [target (get objects id)]
              (let [parent-id (cph/get-parent id objects)
                    frame-id  (:frame-id target)
                    parent    (get objects parent-id)
                    objects   (dissoc objects id)]
                (cond-> objects
                  (and (not= parent-id frame-id)
                       (= :group (:type parent)))
                  (update-in [parent-id :shapes] (fn [s] (filterv #(not= % id) s)))

                  (and (:shape-ref parent) (not ignore-touched))
                  (update-in [parent-id :touched] cph/set-touched-group :shapes-group)

                  (contains? objects frame-id)
                  (update-in [frame-id :shapes] (fn [s] (filterv #(not= % id) s)))

                  (seq (:shapes target))   ; Recursive delete all
                                           ; dependend objects
                  (as-> $ (reduce delete-object $ (:shapes target)))))
              objects))]
    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] delete-object id)
      (d/update-in-when data [:components component-id :objects] delete-object id))))

(defn rotation-modifiers
  [center shape angle]
  (let [displacement (let [shape-center (gsh/center-shape shape)]
                       (-> (gmt/matrix)
                           (gmt/rotate angle center)
                           (gmt/rotate (- angle) shape-center)))]
    {:rotation angle
     :displacement displacement}))

;; reg-objects operation "regenerates" the values for the parent groups
(defmethod process-change :reg-objects
  [data {:keys [page-id component-id shapes]}]
  (letfn [(reg-objects [objects]
            (reduce #(update %1 %2 update-group %1) objects
                    (sequence (comp
                               (mapcat #(cons % (cph/get-parents % objects)))
                               (map #(get objects %))
                               (filter #(= (:type %) :group))
                               (map :id)
                               (distinct))
                              shapes)))
          (update-group [group objects]
            (let [children (->> (if (:masked-group? group)
                                  [(first (:shapes group))]
                                  (:shapes group))
                                (map #(get objects %)))]
              (gsh/update-group-selrect group children)))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] reg-objects)
      (d/update-in-when data [:components component-id :objects] reg-objects))))

(defmethod process-change :mov-objects
  [data {:keys [parent-id shapes index page-id component-id ignore-touched]}]
  (letfn [(is-valid-move? [objects shape-id]
            (let [invalid-targets (cph/calculate-invalid-targets shape-id objects)]
              (and (not (invalid-targets parent-id))
                   (cph/valid-frame-target shape-id parent-id objects))))

          (insert-items [prev-shapes index shapes]
            (let [prev-shapes (or prev-shapes [])]
              (if index
                (cph/insert-at-index prev-shapes index shapes)
                (cph/append-at-the-end prev-shapes shapes))))

          (check-insert-items [prev-shapes parent index shapes]
            (if-not (:masked-group? parent)
              (insert-items prev-shapes index shapes)
              ;; For masked groups, the first shape is the mask
              ;; and it cannot be moved.
              (let [mask-id (first prev-shapes)
                    other-ids (rest prev-shapes)
                    not-mask-shapes (strip-id shapes mask-id)
                    new-index (if (nil? index) nil (max (dec index) 0))
                    new-shapes (insert-items other-ids new-index not-mask-shapes)]
                (d/concat [mask-id] new-shapes))))

          (strip-id [coll id]
            (filterv #(not= % id) coll))

          (add-to-parent [parent index shapes]
            (cond-> parent
              true
              (update :shapes check-insert-items parent index shapes)

              (and (:shape-ref parent) (= (:type parent) :group) (not ignore-touched))
              (update :touched cph/set-touched-group :shapes-group)))

          (remove-from-old-parent [cpindex objects shape-id]
            (let [prev-parent-id (get cpindex shape-id)]
              ;; Do nothing if the parent id of the shape is the same as
              ;; the new destination target parent id.
              (if (= prev-parent-id parent-id)
                objects
                (loop [sid shape-id
                       pid prev-parent-id
                       objects objects]
                  (let [obj (get objects pid)]
                    (if (and (= 1 (count (:shapes obj)))
                             (= sid (first (:shapes obj)))
                             (= :group (:type obj)))
                      (recur pid
                             (:parent-id obj)
                             (dissoc objects pid))
                      (cond-> objects
                        true
                        (update-in [pid :shapes] strip-id sid)

                        (and (:shape-ref obj)
                             (= (:type obj) :group)
                             (not ignore-touched))
                        (update-in [pid :touched]
                                   cph/set-touched-group :shapes-group))))))))

          (update-parent-id [objects id]
            (update objects id assoc :parent-id parent-id))

          ;; Updates the frame-id references that might be outdated
          (assign-frame-id [frame-id objects id]
            (let [objects (update objects id assoc :frame-id frame-id)
                  obj     (get objects id)]
              (cond-> objects
                ;; If we moving frame, the parent frame is the root
                ;; and we DO NOT NEED update children because the
                ;; children will point correctly to the frame what we
                ;; are currently moving
                (not= :frame (:type obj))
                (as-> $$ (reduce (partial assign-frame-id frame-id) $$ (:shapes obj))))))

          (move-objects [objects]
            (let [valid?   (every? (partial is-valid-move? objects) shapes)

                  ;; Create a index of shape ids pointing to the
                  ;; corresponding parents; used mainly for update old
                  ;; parents after move operation.
                  cpindex  (reduce (fn [index id]
                                     (let [obj (get objects id)]
                                       (assoc! index id (:parent-id obj))))
                                   (transient {})
                                   (keys objects))
                  cpindex  (persistent! cpindex)

                  parent   (get objects parent-id)
                  frame-id (if (= :frame (:type parent))
                             (:id parent)
                             (:frame-id parent))]

              (if (and valid? (seq shapes))
                (as-> objects $
                  ;; Add the new shapes to the parent object.
                  (update $ parent-id #(add-to-parent % index shapes))

                  ;; Update each individual shapre link to the new parent
                  (reduce update-parent-id $ shapes)

                  ;; Analyze the old parents and clear the old links
                  ;; only if the new parrent is different form old
                  ;; parent.
                  (reduce (partial remove-from-old-parent cpindex) $ shapes)

                  ;; Ensure that all shapes of the new parent has a
                  ;; correct link to the topside frame.
                  (reduce (partial assign-frame-id frame-id) $ shapes))
              objects)))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] move-objects)
      (d/update-in-when data [:components component-id :objects] move-objects))))

(defmethod process-change :add-page
  [data {:keys [id name page]}]
  (cond
    (and (string? name) (uuid? id))
    (let [page (assoc empty-page-data
                      :id id
                      :name name)]
      (-> data
          (update :pages conj id)
          (update :pages-index assoc id page)))

    (map? page)
    (-> data
        (update :pages conj (:id page))
        (update :pages-index assoc (:id page) page))

    :else
    (ex/raise :type :conflict
              :hint "name or page should be provided, never both")))

(defmethod process-change :mod-page
  [data {:keys [id name]}]
  (d/update-in-when data [:pages-index id] assoc :name name))

(defmethod process-change :del-page
  [data {:keys [id]}]
  (-> data
      (update :pages (fn [pages] (filterv #(not= % id) pages)))
      (update :pages-index dissoc id)))

(defmethod process-change :mov-page
  [data {:keys [id index]}]
  (update data :pages cph/insert-at-index index [id]))

(defmethod process-change :add-color
  [data {:keys [color]}]
  (update data :colors assoc (:id color) color))

(defmethod process-change :mod-color
  [data {:keys [color]}]
  (d/assoc-in-when data [:colors (:id color)] color))

(defmethod process-change :del-color
  [data {:keys [id]}]
  (update data :colors dissoc id))

(defmethod process-change :add-recent-color
  [data {:keys [color]}]
  ;; Moves the color to the top of the list and then truncates up to 15
  (update data :recent-colors (fn [rc]
                                (let [rc (conj (filterv (comp not #{color}) (or rc [])) color)]
                                  (if (> (count rc) 15)
                                    (subvec rc 1)
                                    rc)))))

;; -- Media

(defmethod process-change :add-media
  [data {:keys [object]}]
  (update data :media assoc (:id object) object))

(defmethod process-change :mod-media
  [data {:keys [object]}]
  (d/update-in-when data [:media (:id object)] merge object))

(defmethod process-change :del-media
  [data {:keys [id]}]
  (update data :media dissoc id))

;; -- Components

(defmethod process-change :add-component
  [data {:keys [id name shapes]}]
  (assoc-in data [:components id]
            {:id id
             :name name
             :objects (d/index-by :id shapes)}))

(defmethod process-change :mod-component
  [data {:keys [id name objects]}]
  (update-in data [:components id]
             #(cond-> %
                (some? name)
                (assoc :name name)

                (some? objects)
                (assoc :objects objects))))

(defmethod process-change :del-component
  [data {:keys [id]}]
  (d/dissoc-in data [:components id]))

;; -- Typography

(defmethod process-change :add-typography
  [data {:keys [typography]}]
  (update data :typographies assoc (:id typography) typography))

(defmethod process-change :mod-typography
  [data {:keys [typography]}]
  (d/update-in-when data [:typographies (:id typography)] merge typography))

(defmethod process-change :del-typography
  [data {:keys [id]}]
  (update data :typographies dissoc id))

;; -- Operations

(defmethod process-operation :set
  [shape op]
  (let [attr      (:attr op)
        val       (:val op)
        ignore    (:ignore-touched op)
        shape-ref (:shape-ref shape)
        group     (get component-sync-attrs attr)]

    (cond-> shape
      (and shape-ref group (not ignore) (not= val (get shape attr))
           ;; FIXME: it's difficult to tell if the geometry changes affect
           ;;        an individual shape inside the component, or are for
           ;;        the whole component (in which case we shouldn't set
           ;;        touched). For the moment we disable geometry touched.
           (not= group :geometry-group))
      (update :touched cph/set-touched-group group)

      (nil? val)
      (dissoc attr)

      (some? val)
      (assoc attr val))))

(defmethod process-operation :set-touched
  [shape op]
  (let [touched (:touched op)
        shape-ref (:shape-ref shape)]
    (if (or (nil? shape-ref) (nil? touched) (empty? touched))
      (dissoc shape :touched)
      (assoc shape :touched touched))))

(defmethod process-operation :default
  [_ op]
  (ex/raise :type :not-implemented
            :code :operation-not-implemented
            :context {:type (:type op)}))

