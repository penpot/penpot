(ns uxbox.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]))

;; --- Specs

(s/def ::id ::cs/uuid)
(s/def ::name string?)
(s/def ::type keyword?)

;; Metadata related
(s/def ::grid-x-axis ::cs/number)
(s/def ::grid-y-axis ::cs/number)
(s/def ::grid-color string?)
(s/def ::background string?)
(s/def ::background-opacity ::cs/number)

;; Page related
(s/def ::file-id ::cs/uuid)
(s/def ::user ::cs/uuid)
(s/def ::created-at ::cs/inst)
(s/def ::modified-at ::cs/inst)
(s/def ::version ::cs/number)
(s/def ::ordering ::cs/number)

;; Page Data related
(s/def ::shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def ::shapes (s/coll-of ::cs/uuid :kind vector?))
(s/def ::canvas (s/coll-of ::cs/uuid :kind vector?))

(s/def ::shapes-by-id
  (s/map-of uuid? ::shape))

;; Main

(s/def ::data
  (s/keys :req-un [::shapes ::canvas ::shapes-by-id]))


(s/def ::metadata
  (s/keys :opt-un [::grid-y-axis
                   ::grid-x-axis
                   ::grid-color
                   ::background
                   ::background-opacity]))

(s/def ::operation
  (s/or :mod-shape (s/cat :name #(= % :mod-shape)
                          :id uuid?
                          :attr keyword?
                          :value any?)
        :add-shape (s/cat :name #(= % :add-shape)
                          :id uuid?
                          :data any?)
        :del-shape (s/cat :name #(= % :del-shape)
                          :id uuid?)
        :add-canvas (s/cat :name #(= % :add-canvas)
                           :id uuid?
                           :data any?)
        :del-canvas (s/cat :name #(= % :del-canvas)
                           :id uuid?)))

(s/def ::operations
  (s/coll-of ::operation :kind vector?))


;; --- Operations Processing Impl

(declare process-operation)
(declare process-mod-shape)
(declare process-add-shape)
(declare process-del-shape)
(declare process-add-canvas)
(declare process-del-canvas)

(defn process-ops
  [data operations]
  (->> (cs/conform ::operations operations)
       (reduce process-operation data)))

(defn- process-operation
  [data operation]
  (case (first operation)
    :mod-shape (process-mod-shape data operation)
    :add-shape (process-add-shape data operation)
    :del-shape (process-del-shape data operation)
    :add-canvas (process-add-canvas data operation)
    :del-canvas (process-del-canvas data operation)))

(defn- process-mod-shape
  [data {:keys [id attr value]}]
  (update-in data [:shapes-by-id id] assoc attr value))

(defn- process-add-shape
  [data {:keys [id data]}]
  (-> data
      (update :shapes conj id)
      (update :shapes-by-id assoc id data)))

(defn- process-del-shape
  [data {:keys [id attr value]}]
  (-> data
      (update :shapes (fn [s] (filterv #(not= % id) s)))
      (update :shapes-by-id dissoc id)))

(defn- process-add-canvas
  [data {:keys [id data]}]
  (-> data
      (update :canvas conj id)
      (update :shapes-by-id assoc id data)))

(defn- process-del-canvas
  [data {:keys [id attr value]}]
  (-> data
      (update :canvas (fn [s] (filterv #(not= % id) s)))
      (update :shapes-by-id dissoc id)))

