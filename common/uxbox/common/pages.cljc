;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]))

;; TODO: should be replaced when uuid is unified under
;; uxbox.common.uuid namespace.
(def uuid-zero #uuid "00000000-0000-0000-0000-000000000000")

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
                   ::width ::height]))

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
  (s/keys :req-un [::options
                   ::version
                   ::objects]))

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

(defmethod change-spec-impl :add-obj [_]
  (s/keys :req-un [::id ::frame-id ::obj]
          :opt-un [::session-id ::parent-id]))

(defmethod change-spec-impl :mod-obj [_]
  (s/keys :req-un [::id ::operations]
          :opt-un [::session-id]))

(defmethod change-spec-impl :del-obj [_]
  (s/keys :req-un [::id]
          :opt-un [::session-id]))

(defmethod change-spec-impl :mov-objects [_]
  (s/keys :req-un [::parent-id ::shapes]
          :opt-un [::index]))

(s/def ::change (s/multi-spec change-spec-impl :type))
(s/def ::changes (s/coll-of ::change))

(def root #uuid "00000000-0000-0000-0000-000000000000")

(def default-page-data
  "A reference value of the empty page data."
  {:version 3
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
  {:frame-id uuid-zero
   :fill-color "#ffffff"
   :fill-opacity 1
   :shapes []})

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

(declare insert-at-index)

(defmethod process-change :add-obj
  [data {:keys [id obj frame-id parent-id index] :as change}]
  (let [parent-id (or parent-id frame-id)
        objects (:objects data)]
    (when (and (contains? objects parent-id)
               (contains? objects frame-id))
      (let [obj (assoc obj
                   :frame-id frame-id
                   :id id)]
        (-> data
            (update :objects assoc id obj)
            (update-in [:objects parent-id :shapes]
                       (fn [shapes]
                         (let [shapes (or shapes [])]
                           (cond
                             (some #{id} shapes) shapes
                             (nil? index) (conj shapes id)
                             :else (insert-at-index shapes index [id]))))))))))

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
    (let [data (update data :objects dissoc id)]
      (cond-> data
        (contains? (:objects data) frame-id)
        (update-in [:objects frame-id :shapes] (fn [s] (filterv #(not= % id) s)))

        (seq shapes)   ; Recursive delete all dependend objects
        (as-> $ (reduce #(or (process-change %1 {:type :del-obj :id %2}) %1) $ shapes))))))

(defn- calculate-child-parent-map
  [objects]
  (let [red-fn
        (fn [acc {:keys [id shapes]}]
          ;; Insert every pair shape -> parent into accumulated value
          (into acc (map #(vector % id) (or shapes []))))]
    (reduce red-fn {} (vals objects))))

(defn- calculate-invalid-targets
  [shape-id objects]
  (let [result #{shape-id}
        children (get-in objects [shape-id :shape])
        reduce-fn (fn [result child-id]
                    (into result (calculate-invalid-targets child-id objects)))]
    (reduce reduce-fn result children)))

(defn- insert-at-index
  [shapes index ids]
  (let [[before after] (split-at index shapes)
        p? (set ids)]
    (d/concat []
              (remove p? before)
              ids
              (remove p? after))))


(defmethod process-change :mov-objects
  [data {:keys [parent-id shapes index] :as change}]
  (let [child->parent (calculate-child-parent-map (:objects data))
        ;; Check if the move from shape-id -> parent-id is valid
        is-valid-move
        (fn [shape-id]
          (let [invalid (calculate-invalid-targets shape-id (:objects data))]
            (not (invalid parent-id))))

        valid? (every? is-valid-move shapes)

        ;; Add items into the :shapes property of the target parent-id
        insert-items
        (fn [prev-shapes]
          (let [prev-shapes (or prev-shapes [])]
            (if index
              (insert-at-index prev-shapes index shapes)
              (reduce (fn [acc id]
                        (if (some #{id} acc)
                          acc
                          (conj acc id)))
                      prev-shapes
                      shapes))))

        strip-id
        (fn [id]
          (fn [coll] (filterv #(not= % id) coll)))

        ;; Remove from the old :shapes the references that have been moved
        remove-in-parent
        (fn [data shape-id]
          (let [parent-id' (get child->parent shape-id)]
            ;; Do nothing if the parent id of the shape is the same as
            ;; the new destination target parent id.
            (if (= parent-id' parent-id)
              data
              (let [parent (-> (get-in data [:objects parent-id'])
                               (update :shapes (strip-id shape-id)))]
                ;; When the group is empty we should remove it
                (if (and (= :group (:type parent))
                         (empty? (:shapes parent)))
                  (-> data
                      (update :objects dissoc (:id parent))
                      (update-in [:objects (:frame-id parent) :shapes] (strip-id (:id parent))))
                  (update data :objects assoc parent-id' parent))))))

        parent (get-in data [:objects parent-id])
        frame  (if (= :frame (:type parent))
                 parent
                 (get-in data [:objects (:frame-id parent)]))

        frame-id (:id frame)

        ;; Updates the frame-id references that might be outdated
        update-frame-ids
        (fn update-frame-ids [data id]
          (let [data (assoc-in data [:objects id :frame-id] frame-id)
                obj (get-in data [:objects id])]
            (cond-> data
              (not= :frame (:type obj))
              (as-> $$ (reduce update-frame-ids $$ (:shapes obj))))))]

    (when valid?
      (as-> data $
        (update-in $ [:objects parent-id :shapes] insert-items)
        (reduce remove-in-parent $ shapes)
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


