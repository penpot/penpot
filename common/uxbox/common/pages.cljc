;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.data :as d]
   [uxbox.common.pages-helpers :as cph]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]))

(def page-version 5)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Transformation Changes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Specs

(s/def ::id uuid?)
(s/def ::shape-id uuid?)
(s/def ::session-id uuid?)
(s/def ::name string?)
(s/def ::parent-id uuid?)

;; Page Options
(s/def ::grid-x number?)
(s/def ::grid-y number?)
(s/def ::grid-color string?)

(s/def ::options
  (s/keys :opt-un [::grid-y
                   ::grid-x
                   ::grid-color]))

;; Interactions

(s/def ::event-type #{:click}) ; In the future we will have more options
(s/def ::action-type #{:navigate})
(s/def ::destination uuid?)

(s/def ::interaction
  (s/keys :req-un [::event-type
                   ::action-type
                   ::destination]))

(s/def ::interactions (s/coll-of ::interaction :kind vector?))

;; Page Data related
(s/def ::blocked boolean?)
(s/def ::collapsed boolean?)
(s/def ::content any?)
(s/def ::fill-color string?)
(s/def ::fill-opacity number?)
(s/def ::font-family string?)
(s/def ::font-size number?)
(s/def ::font-style string?)
(s/def ::font-weight string?)
(s/def ::hidden boolean?)
(s/def ::letter-spacing number?)
(s/def ::line-height number?)
(s/def ::locked boolean?)
(s/def ::page-id uuid?)
(s/def ::proportion number?)
(s/def ::proportion-lock boolean?)
(s/def ::rx number?)
(s/def ::ry number?)
(s/def ::stroke-color string?)
(s/def ::stroke-opacity number?)
(s/def ::stroke-style #{:solid :dotted :dashed :mixed :none})
(s/def ::stroke-width number?)
(s/def ::stroke-alignment #{:center :inner :outer})
(s/def ::text-align #{"left" "right" "center" "justify"})
(s/def ::type #{:rect :path :circle :image :text :canvas :curve :icon :frame :group})
(s/def ::x number?)
(s/def ::y number?)
(s/def ::cx number?)
(s/def ::cy number?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::index integer?)
(s/def ::x1 number?)
(s/def ::y1 number?)
(s/def ::x2 number?)
(s/def ::y2 number?)

(s/def ::selrect (s/keys :req-un [::x
                                  ::y
                                  ::x1
                                  ::y1
                                  ::x2
                                  ::y2
                                  ::width
                                  ::height]))

(s/def ::point (s/keys :req-un [::x ::y]))
(s/def ::points (s/coll-of ::point :kind vector?))

(s/def ::shape-attrs
  (s/keys :opt-un [::blocked
                   ::collapsed
                   ::content
                   ::fill-color
                   ::fill-opacity
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
                   ::rx ::ry
                   ::cx ::cy
                   ::x ::y
                   ::stroke-color
                   ::stroke-opacity
                   ::stroke-style
                   ::stroke-width
                   ::stroke-alignment
                   ::text-align
                   ::width ::height
                   ::interactions
                   ::selrect
                   ::points]))

(s/def ::minimal-shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def ::shape
  (s/and ::minimal-shape ::shape-attrs
         (s/keys :opt-un [::id])))

(s/def ::shapes (s/coll-of uuid? :kind vector?))
(s/def ::canvas (s/coll-of uuid? :kind vector?))

(s/def ::objects
  (s/map-of uuid? ::shape))

(s/def ::data
  (s/keys :req-un [::version
                   ::options
                   ::objects]))

(s/def ::ids (s/coll-of ::us/uuid))
(s/def ::attr keyword?)
(s/def ::val  any?)
(s/def ::frame-id uuid?)
(s/def ::loc #{:top :bottom :up :down})

(defmulti operation-spec-impl :type)

(defmethod operation-spec-impl :set [_]
  (s/keys :req-un [::attr ::val]))

(defmethod operation-spec-impl :abs-order [_]
  (s/keys :req-un [::id ::index]))

(defmethod operation-spec-impl :rel-order [_]
  (s/keys :req-un [::id ::loc]))

(s/def ::operation (s/multi-spec operation-spec-impl :type))
(s/def ::operations (s/coll-of ::operation))

(defmulti change-spec-impl :type)

(s/def :set-option/option any? #_(s/or keyword? (s/coll-of keyword?)))
(s/def :set-option/value any?)

(defmethod change-spec-impl :set-option [_]
  (s/keys :req-un [:set-option/option :set-option/value]))

(defmethod change-spec-impl :add-obj [_]
  (s/keys :req-un [::id ::frame-id ::obj]
          :opt-un [::parent-id]))

(defmethod change-spec-impl :mod-obj [_]
  (s/keys :req-un [::id ::operations]))

(defmethod change-spec-impl :del-obj [_]
  (s/keys :req-un [::id]))

(defmethod change-spec-impl :reg-objects [_]
  (s/keys :req-un [::shapes]))

(defmethod change-spec-impl :mov-objects [_]
  (s/keys :req-un [::parent-id ::shapes]
          :opt-un [::index]))

(s/def ::change (s/multi-spec change-spec-impl :type))
(s/def ::changes (s/coll-of ::change))

(def root uuid/zero)

(def default-page-data
  "A reference value of the empty page data."
  {:version page-version
   :options {}
   :objects
   {root
    {:id root
     :type :frame
     :name "root"
     :shapes []}}})

(def default-shape-attrs
  {:fill-color "#000000"
   :fill-opacity 1})

(def default-frame-attrs
  {:frame-id uuid/zero
   :fill-color "#ffffff"
   :fill-opacity 1
   :shapes []})

(def ^:private default-color "#b1b2b5") ;; $color-gray-20

(def ^:private minimal-shapes
  [{:type :rect
    :name "Rect"
    :fill-color default-color
    :stroke-alignment :center}
   {:type :image}
   {:type :icon}
   {:type :circle
    :name "Circle"
    :fill-color default-color}
   {:type :path
    :name "Path"
    :stroke-style :solid
    :stroke-color "#000000"
    :stroke-width 2
    :stroke-alignment :center
    :fill-color "#000000"
    :fill-opacity 0
    :segments []}
   {:type :frame
    :stroke-style :none
    :stroke-alignment :center
    :name "Artboard"}
   {:type :curve
    :name "Path"
    :stroke-style :solid
    :stroke-color "#000000"
    :stroke-width 2
    :stroke-alignment :center
    :fill-color "#000000"
    :fill-opacity 0
    :segments []}
   {:type :text
    :name "Text"
    :content nil}])

(defn make-minimal-shape
  [type]
  (let [shape (d/seek #(= type (:type %)) minimal-shapes)]
    (assert shape "unexpected shape type")
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

;; --- Changes Processing Impl

(defmulti process-change
  (fn [data change] (:type change)))

(defmulti process-operation
  (fn [_ op] (:type op)))

(defn process-changes
  [data items]
  (->> (us/verify ::changes items)
       (reduce #(do
                  ;; (prn "process-change" (:type %2) (:id %2))
                  (or (process-change %1 %2) %1))
               data)))

(defmethod process-change :set-option
  [data {:keys [option value]}]
  (let [path (if (seqable? option) option [option])]
    (if value
      (assoc-in data (into [:options] path) value)
      (assoc data :options (d/dissoc-in (:options data) path)))))

(defmethod process-change :add-obj
  [data {:keys [id obj frame-id parent-id index] :as change}]
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
                             :else (cph/insert-at-index shapes index [id]))))))))))

(defmethod process-change :mod-obj
  [data {:keys [id operations] :as change}]
  (update data :objects
          (fn [objects]
            (if-let [obj (get objects id)]
              (assoc objects id (reduce process-operation obj operations))
              objects))))

(defmethod process-change :del-obj
  [data {:keys [id] :as change}]
  (when-let [{:keys [frame-id shapes] :as obj} (get-in data [:objects id])]
    (let [objects   (:objects data)
          parent-id (cph/get-parent id objects)
          parent    (get objects parent-id)
          data      (update data :objects dissoc id)]
      (cond-> data
        (and (not= parent-id frame-id)
             (= :group (:type parent)))
        (update-in [:objects parent-id :shapes] (fn [s] (filterv #(not= % id) s)))

        (contains? objects frame-id)
        (update-in [:objects frame-id :shapes] (fn [s] (filterv #(not= % id) s)))

        (seq shapes)   ; Recursive delete all dependend objects
        (as-> $ (reduce #(or (process-change %1 {:type :del-obj :id %2}) %1) $ shapes))))))

(defmethod process-change :reg-objects
  [data {:keys [shapes]}]
  (let [objects (:objects data)]
    (loop [shapes shapes data data]
      (if (seq shapes)
        (let [item (get objects (first shapes))]
          (if (= :group (:type item))
            (recur
             (rest shapes)
             (update-in data [:objects (:id item)]
                        (fn [{:keys [shapes] :as obj}]
                          (let [shapes (->> shapes
                                            (map (partial get objects))
                                            (filter identity))]
                            (if (seq shapes)
                              (let [selrect (geom/selection-rect shapes)]
                                (as-> obj $
                                  (assoc $
                                         :x (:x selrect)
                                         :y (:y selrect)
                                         :width (:width selrect)
                                         :height (:height selrect))
                                  (assoc $ :points (geom/shape->points $))
                                  (assoc $ :selrect (geom/points->selrect (:points $)))))
                              obj)))))
            (recur (rest shapes) data)))
        data))))

(defmethod process-change :mov-objects
  [data {:keys [parent-id shapes index] :as change}]
  (let [
        ;; Check if the move from shape-id -> parent-id is valid

        is-valid-move
        (fn [shape-id]
          (let [invalid-targets (cph/calculate-invalid-targets shape-id (:objects data))]
            (and (not (invalid-targets parent-id))
                 (cph/valid-frame-target shape-id parent-id (:objects data)))))

        valid? (every? is-valid-move shapes)

        ;; Add items into the :shapes property of the target parent-id
        insert-items
        (fn [prev-shapes]
          (let [prev-shapes (or prev-shapes [])]
            (if index
              (cph/insert-at-index prev-shapes index shapes)
              (reduce (fn [acc id]
                        (if (some #{id} acc)
                          acc
                          (conj acc id)))
                      prev-shapes
                      shapes))))

        strip-id
        (fn [id]
          (fn [coll] (filterv #(not= % id) coll)))

        cpindex
        (reduce
         (fn [index id]
           (let [obj (get-in data [:objects id])]
             (assoc index id (:parent-id obj))))
         {} (keys (:objects data)))

        remove-from-old-parent
        (fn remove-from-old-parent [data shape-id]
          (let [prev-parent-id (get cpindex shape-id)]
            ;; Do nothing if the parent id of the shape is the same as
            ;; the new destination target parent id.
            (if (= prev-parent-id parent-id)
              data
              (loop [sid  shape-id
                     pid  prev-parent-id
                     data data]
                (let [obj (get-in data [:objects pid])]
                  (if (and (= 1 (count (:shapes obj)))
                           (= sid (first (:shapes obj)))
                           (= :group (:type obj)))
                    (recur pid
                           (:parent-id obj)
                           (update data :objects dissoc pid))
                    (update-in data [:objects pid :shapes] (strip-id sid))))))))

        parent (get-in data [:objects parent-id])
        frame  (if (= :frame (:type parent))
                 parent
                 (get-in data [:objects (:frame-id parent)]))

        frame-id (:id frame)

        ;; Update parent-id references.
        update-parent-id
        (fn [data id]
          (update-in data [:objects id] assoc :parent-id parent-id))

        ;; Updates the frame-id references that might be outdated
        update-frame-ids
        (fn update-frame-ids [data id]
          (let [data (assoc-in data [:objects id :frame-id] frame-id)
                obj  (get-in data [:objects id])]
            (cond-> data
              (not= :frame (:type obj))
              (as-> $$ (reduce update-frame-ids $$ (:shapes obj))))))]

    (when valid?
      (as-> data $
        (update-in $ [:objects parent-id :shapes] insert-items)
        (reduce update-parent-id $ shapes)
        (reduce remove-from-old-parent $ shapes)
        (reduce update-frame-ids $ (get-in $ [:objects parent-id :shapes]))))))


(defmethod process-operation :set
  [shape op]
  (let [attr (:attr op)
        val  (:val op)]
    (if (nil? val)
      (dissoc shape attr)
      (assoc shape attr val))))

(defmethod process-operation :abs-order
  [obj {:keys [id index]}]
  (assert (vector? (:shapes obj)) ":shapes should be a vector")
  (update obj :shapes (fn [items]
                        (let [[b a] (->> (remove #(= % id) items)
                                         (split-at index))]
                          (vec (concat b [id] a))))))


(defmethod process-operation :rel-order
  [obj {:keys [id loc] :as change}]
  (assert (vector? (:shapes obj)) ":shapes should be a vector")
  (let [shapes (:shapes obj)
        cindex (d/index-of shapes id)
        nindex (case loc
                 :top (- (count shapes) 1)
                 :down (max 0 (- cindex 1))
                 :up (min (- (count shapes) 1) (inc cindex))
                 :bottom 0)]
    (update obj :shapes
            (fn [shapes]
              (let [[fst snd] (->> (remove #(= % id) shapes)
                                   (split-at nindex))]
                (d/concat [] fst [id] snd))))))


(defmethod process-operation :default
  [shape op]
  (ex/raise :type :operation-not-implemented
            :context {:type (:type op)}))
