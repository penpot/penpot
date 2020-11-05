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
   [app.common.geom.shapes :as geom]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]))

(def file-version 1)
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

(s/def ::safe-integer
  #(and
    (integer? %)
    (>= % min-safe-int)
    (<= % max-safe-int)))
(s/def ::component-id uuid?)
(s/def ::component-file uuid?)
(s/def ::component-root? boolean?)
(s/def ::shape-ref uuid?)

(s/def ::safe-number
  #(and
    (number? %)
    (>= % min-safe-int)
    (<= % max-safe-int)))

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
(s/def :internal.shape/fill-gradient (s/nilable ::gradient))
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

(s/def :internal.shape/point
  (s/keys :req-un [:internal.shape/x :internal.shape/y]))

(s/def :internal.shape/points
  (s/coll-of :internal.shape/point :kind vector?))

(s/def ::shape-attrs
  (s/keys :opt-un [:internal.shape/blocked
                   :internal.shape/collapsed
                   :internal.shape/content
                   :internal.shape/fill-color
                   :internal.shape/fill-color-ref-file
                   :internal.shape/fill-color-ref-id
                   :internal.shape/fill-opacity
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
                   :internal.shape/cx
                   :internal.shape/cy
                   :internal.shape/x
                   :internal.shape/y
                   :internal.shape/exports
                   :internal.shape/stroke-color
                   :internal.shape/stroke-color-ref-file
                   :internal.shape/stroke-color-ref-id
                   :internal.shape/stroke-opacity
                   :internal.shape/stroke-style
                   :internal.shape/stroke-width
                   :internal.shape/stroke-alignment
                   :internal.shape/text-align
                   :internal.shape/width
                   :internal.shape/height
                   :internal.shape/interactions
                   :internal.shape/selrect
                   :internal.shape/points
                   :internal.shape/masked-group?
                   :internal.shape/shadow
                   :internal.shape/blur]))

(def component-sync-attrs {:fill-color            :fill-group
                           :fill-color-ref-file   :fill-group
                           :fill-color-ref-id     :fill-group
                           :fill-opacity          :fill-group
                           :content               :text-content-group
                           :font-family           :text-font-group
                           :font-size             :text-font-group
                           :font-style            :text-font-group
                           :font-weight           :text-font-group
                           :letter-spacing        :text-display-group
                           :line-height           :text-display-group
                           :text-align            :text-display-group
                           :stroke-color          :stroke-group
                           :stroke-color-ref-file :stroke-group
                           :stroke-color-ref-id   :stroke-group
                           :stroke-opacity        :stroke-group
                           :stroke-style          :stroke-group
                           :stroke-width          :stroke-group
                           :stroke-alignment      :stroke-group
                           :width                 :size-group
                           :height                :size-group
                           :proportion            :size-group
                           :rx                    :radius-group
                           :ry                    :radius-group
                           :masked-group?         :mask-group})

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

(defmethod change-spec :add-obj [_]
  (s/keys :req-un [::id ::page-id ::frame-id
                   :internal.changes.add-obj/obj]
          :opt-un [::parent-id]))

(s/def ::operation (s/multi-spec operation-spec :type))
(s/def ::operations (s/coll-of ::operation))

(defmethod change-spec :mod-obj [_]
  (s/keys :req-un [::id (or ::page-id ::component-id) ::operations]))

(defmethod change-spec :del-obj [_]
  (s/keys :req-un [::id ::page-id]))

(s/def :internal.changes.reg-objects/shapes
  (s/coll-of uuid? :kind vector?))

(defmethod change-spec :reg-objects [_]
  (s/keys :req-un [::page-id :internal.changes.reg-objects/shapes]))

(defmethod change-spec :mov-objects [_]
  (s/keys :req-un [::page-id ::parent-id ::shapes]
          :opt-un [::index]))

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
  (s/keys :req-un [::id ::name :internal.changes.add-component/shapes]))

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
    :stroke-opacity 1
    :segments []}

   {:type :frame
    :name "Artboard"
    :fill-color "#ffffff"
    :fill-opacity 1
    :stroke-style :none
    :stroke-alignment :center
    :stroke-width 0
    :stroke-color "#000000"
    :stroke-opacity 0}

   {:type :curve
    :name "Path"
    :fill-color "#000000"
    :fill-opacity 0
    :stroke-style :solid
    :stroke-alignment :center
    :stroke-width 2
    :stroke-color "#000000"
    :stroke-opacity 1
    :segments []}

   {:type :text
    :name "Text"
    :content nil}])

(defn make-minimal-shape
  [type]
  (let [shape (d/seek #(= type (:type %)) minimal-shapes)]
    (when-not shape
      (ex/raise :type :assertion
                :code :shape-type-not-implemented
                :context {:type type}))
    (assoc shape
           :id (uuid/next)
           :x 0
           :y 0
           :width 1
           :height 1
           :selrect {:x 0
                     :x1 0
                     :x2 1
                     :y 0
                     :y1 0
                     :y2 1
                     :width 1
                     :height 1}
           :points []
           :segments [])))

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

(defmulti process-change (fn [data change] (:type change)))
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
  [data {:keys [id obj page-id frame-id parent-id index] :as change}]
  (d/update-in-when data [:pages-index page-id]
                    (fn [data]
                      (let [parent-id (or parent-id frame-id)
                            objects (:objects data)]
                        (when (and (contains? objects parent-id)
                                   (contains? objects frame-id))
                          (let [obj (assoc obj
                                           :frame-id frame-id
                                           :parent-id parent-id
                                           :id id)]
                            (-> data
                                (update :objects assoc id obj)
                                (update-in [:objects parent-id :shapes]
                                           (fn [shapes]
                                             (let [shapes (or shapes [])]
                                               (cond
                                                 (some #{id} shapes) shapes
                                                 (nil? index) (conj shapes id)
                                                 :else (cph/insert-at-index shapes index [id]))))))))))))

(defmethod process-change :mod-obj
  [data {:keys [id page-id component-id operations] :as change}]
  (let [update-fn (fn [objects]
                    (if-let [obj (get objects id)]
                      (assoc objects id (reduce process-operation obj operations))
                      objects))]
    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] update-fn)
      (d/update-in-when data [:components component-id :objects] update-fn))))

(defmethod process-change :del-obj
  [data {:keys [page-id id] :as change}]
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

                  (contains? objects frame-id)
                  (update-in [frame-id :shapes] (fn [s] (filterv #(not= % id) s)))

                  (seq (:shapes target))   ; Recursive delete all
                                           ; dependend objects
                  (as-> $ (reduce delete-object $ (:shapes target)))))
              objects))]
    (d/update-in-when data [:pages-index page-id :objects] delete-object id)))

(defn rotation-modifiers
  [center shape angle]
  (let [displacement (let [shape-center (geom/center shape)]
                       (-> (gmt/matrix)
                           (gmt/rotate angle center)
                           (gmt/rotate (- angle) shape-center)))]
    {:rotation angle
     :displacement displacement}))

(defmethod process-change :reg-objects
  [data {:keys [page-id shapes]}]
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
            (let [gcenter (geom/center group)
                  gxfm    (comp
                           (map #(get objects %))
                           (map #(-> %
                                     (assoc :modifiers
                                            (rotation-modifiers gcenter % (- (:rotation group 0))))
                                     (geom/transform-shape))))
                  inner-shapes (if (:masked-group? group)
                                 [(first (:shapes group))]
                                 (:shapes group))
                  selrect (-> (into [] gxfm inner-shapes)
                              (geom/selection-rect))]

              ;; Rotate the group shape change the data and rotate back again
              (-> group
                  (assoc-in [:modifiers :rotation] (- (:rotation group 0)))
                  (geom/transform-shape)
                  (merge (select-keys selrect [:x :y :width :height]))
                  (assoc-in [:modifiers :rotation] (:rotation group))
                  (geom/transform-shape))))]

    (d/update-in-when data [:pages-index page-id :objects] reg-objects)))

(defmethod process-change :mov-objects
  [data {:keys [parent-id shapes index page-id] :as change}]
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
                      (update-in objects [pid :shapes] strip-id sid)))))))

          (update-parent-id [objects id]
            (update objects id assoc :parent-id parent-id))

          ;; Updates the frame-id references that might be outdated
          (update-frame-ids [frame-id objects id]
            (let [objects (assoc-in objects [id :frame-id] frame-id)
                  obj     (get objects id)]
              (cond-> objects
                (not= :frame (:type obj))
                (as-> $$ (reduce (partial update-frame-ids frame-id) $$ (:shapes obj))))))

          (move-objects [objects]
            (let [valid?  (every? (partial is-valid-move? objects) shapes)
                  cpindex (reduce (fn [index id]
                                    (let [obj (get objects id)]
                                      (assoc! index id (:parent-id obj))))
                                  (transient {})
                                  (keys objects))
                  cpindex (persistent! cpindex)

                  parent  (get-in data [:objects parent-id])
                  parent  (get objects parent-id)
                  frame   (if (= :frame (:type parent))
                            parent
                            (get objects (:frame-id parent)))

                  frm-id  (:id frame)]

              (if valid?
                (as-> objects $
                  (update-in $ [parent-id :shapes] check-insert-items parent index shapes)
                  (reduce update-parent-id $ shapes)
                  (reduce (partial remove-from-old-parent cpindex) $ shapes)
                  (reduce (partial update-frame-ids frm-id) $ (get-in $ [parent-id :shapes])))
                objects)))]

    (d/update-in-when data [:pages-index page-id :objects] move-objects)))

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
    (->> data
         (update :pages conj (:id page)
                 (update :pages-index assoc (:id page) page)))

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
  [data {:keys [id name shapes]}]
  (update-in data [:components id]
             #(assoc %
                     :name name
                     :objects (d/index-by :id shapes))))

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
      (and shape-ref group (not ignore) (not= val (get shape attr)))
      (update :touched #(conj (or % #{}) group))

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
  [shape op]
  (ex/raise :type :not-implemented
            :code :operation-not-implemented
            :context {:type (:type op)}))

