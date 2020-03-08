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
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::shape-id uuid?)
(s/def ::session-id uuid?)
(s/def ::name string?)

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
(s/def ::content string?)
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
(s/def ::stroke-style #{:none :solid :dotted :dashed :mixed})
(s/def ::stroke-width number?)
(s/def ::text-align #{"left" "right" "center" "justify"})
(s/def ::type #{:rect :path :circle :image :text :canvas :curve :icon :frame})
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
(s/def ::parent-id uuid?)
(s/def ::frame-id uuid?)

(defmulti operation-spec-impl :type)

(defmethod operation-spec-impl :set [_]
  (s/keys :req-un [::attr ::val]))

(defmethod operation-spec-impl :mov [_]
  (s/keys :req-un [::id ::index]))

(s/def ::operation (s/multi-spec operation-spec-impl :type))
(s/def ::operations (s/coll-of ::operation))

(defmulti change-spec-impl :type)

(defmethod change-spec-impl :add-obj [_]
  (s/keys :req-un [::id ::frame-id ::obj]
          :opt-un [::session-id]))

(defmethod change-spec-impl :mod-obj [_]
  (s/keys :req-un [::id ::operations]
          :opt-un [::session-id]))

(defmethod change-spec-impl :del-obj [_]
  (s/keys :req-un [::id]
          :opt-un [::session-id]))

(defmethod change-spec-impl :mov-obj [_]
  (s/keys :req-un [::id ::frame-id]
          :opt-un [::session-id]))

;; (defmethod change-spec-impl :mod-shape [_]
;;   (s/keys :req-un [::id ::operations ::session-id]))

;; (defmethod change-spec-impl :mov-shape [_]
;;   (s/keys :req-un [::id ::index ::session-id]))

;; (defmethod change-spec-impl :mod-opts [_]
;;   (s/keys :req-un [::operations ::session-id]))

;; (defmethod change-spec-impl :del-shape [_]
;;   (s/keys :req-un [::id ::session-id]))

;; (defmethod change-spec-impl :del-canvas [_]
;;   (s/keys :req-un [::id ::session-id]))

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

;; --- Changes Processing Impl

(defmulti process-change
  (fn [data change] (:type change)))

(defn process-changes
  [data items]
  (->> (us/verify ::changes items)
       (reduce #(or (process-change %1 %2) %1) data)))

(defmethod process-change :add-obj
  [data {:keys [id obj frame-id index] :as change}]
  (assert (contains? (:objects data) frame-id) "process-change/add-obj")
  (let [obj (assoc obj
                   :frame-id frame-id
                   :id id)]
    (-> data
        (update :objects assoc id obj)
        (update-in [:objects frame-id :shapes]
                   (fn [shapes]
                     (cond
                       (some #{id} shapes)
                       shapes

                       (nil? index)
                       (conj shapes id)

                       :else
                       (let [[before after] (split-at index shapes)]
                         (d/concat [] before [id] after))))))))

(defn- process-obj-operation
  [shape op]
  (case (:type op)
    :set
    (let [attr (:attr op)
          val  (:val op)]
      (if (nil? val)
        (dissoc shape attr)
        (assoc shape attr val)))

    (ex/raise :type :not-implemented
              :hint "TODO")))

(defmethod process-change :mod-obj
  [data {:keys [id operations] :as change}]
  (assert (contains? (:objects data) id) "process-change/mod-obj")
  (update-in data [:objects id]
             #(reduce process-obj-operation % operations)))

(defmethod process-change :mov-obj
  [data {:keys [id frame-id] :as change}]
  (assert (contains? (:objects data) frame-id))
  (let [frame-id' (get-in data [:objects id :frame-id])]
    (when (not= frame-id frame-id')
      (-> data
          (update-in [:objects frame-id' :shapes] (fn [s] (filterv #(not= % id) s)))
          (update-in [:objects id] assoc :frame-id frame-id)
          (update-in [:objects frame-id :shapes] conj id)))))

(defmethod process-change :del-obj
  [data {:keys [id] :as change}]
  (when-let [{:keys [frame-id] :as obj} (get-in data [:objects id])]
    (-> data
        (update :objects dissoc id)
        (update-in [:objects frame-id :shapes]
                   (fn [s] (filterv #(not= % id) s))))))

;; (defn- process-change
;;   [data {:keys [type] :as change}]
;;   (case type
;;     :add-obj (process-add-obj data change)
;;     :mod-obj (process-mod-obj data change)

;;     ;; :add-shape (process-add-shape data change)
;;     ;; :add-canvas (process-add-canvas data change)
;;     ;; :mod-shape (process-mod-shape data change)
;;     ;; :mov-shape (process-mov-shape data change)
;;     ;; :del-shape (process-del-shape data change)
;;     ;; :del-canvas (process-del-canvas data change)
;;     ;; :mod-opts (process-mod-opts data change)
;;     ))

;; (defn- process-add-obj


;; (defn- process-add-shape
;;   [data {:keys [id index shape] :as change}]
;;   (-> data
;;       (update :shapes (fn [shapes]
;;                         (cond
;;                           (some #{id} shapes)
;;                           shapes

;;                           (nil? index)
;;                           (conj shapes id)

;;                           :else
;;                           (let [[before after] (split-at index shapes)]
;;                             (d/concat [] before [id] after)))))
;;       (update :shapes-by-id assoc id shape)))

;; (defn- process-add-canvas
;;   [data {:keys [id shape index] :as change}]
;;   (-> data
;;       (update :canvas (fn [shapes]
;;                         (cond
;;                           (some #{id} shapes)
;;                           shapes

;;                           (nil? index)
;;                           (conj shapes id)

;;                           :else
;;                           (let [[before after] (split-at index shapes)]
;;                             (d/concat [] before [id] after)))))

;;       (update :shapes-by-id assoc id shape)))

;; (defn- process-mod-shape
;;   [data {:keys [id operations] :as change}]
;;   (if (get-in data [:shapes-by-id id])
;;     (update-in data [:shapes-by-id id]
;;                #(reduce (fn [shape [_ att val]]
;;                           (if (nil? val)
;;                             (dissoc shape att)
;;                             (assoc shape att val)))
;;                         % operations))
;;     data))

;; (defn- process-mod-opts
;;   [data {:keys [operations]}]
;;   (update data :options
;;           #(reduce (fn [options [_ att val]]
;;                      (if (nil? val)
;;                        (dissoc options att)
;;                        (assoc options att val)))
;;                    % operations)))

;; (defn- process-mov-shape
;;   [data {:keys [id index]}]
;;   (let [shapes (:shapes data)
;;         current-index (d/index-of shapes id)
;;         shapes' (into [] (remove #(= % id) shapes))]
;;     (cond
;;       (= index current-index)
;;       data

;;       (nil? current-index)
;;       (assoc data :shapes (d/concat [id] shapes'))

;;       :else
;;       (let [[before after] (split-at index shapes')]
;;         (assoc data :shapes (d/concat [] before [id] after))))))

;; (defn- process-del-shape
;;   [data {:keys [id] :as change}]
;;   (-> data
;;       (update :shapes (fn [s] (filterv #(not= % id) s)))
;;       (update :shapes-by-id dissoc id)))

;; (defn- process-del-canvas
;;   [data {:keys [id] :as change}]
;;   (-> data
;;       (update :canvas (fn [s] (filterv #(not= % id) s)))
;;       (update :shapes-by-id dissoc id)))

