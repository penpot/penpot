(ns uxbox.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.spec :as cs]))

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

(s/def ::opeation
  (s/or :mod-shape (s/cat :name #(= % :mod-shape)
                          :id uuid?
                          :attr keyword?
                          :value any?)
        :add-shape (s/cat :name #(= % :add-shape)
                          :id uuid?
                          :data any?)
        :del-shape (s/cat :name #(= % :del-shape)
                          :id uuid?)))

;; --- Operations Processing Impl

(declare process-operation)
(declare process-add-shape)
(declare process-mod-shape)
(declare process-del-shape)

(defn process-ops
  [data operations]
  (reduce process-operation data operations))

(defn- process-operation
  [data operation]
  (case (first operation)
    :add-shape (process-add-shape data operation)
    :mod-shape (process-mod-shape data operation)
    :del-shape (process-del-shape data operation)))

(defn- process-add-shape
  [data {:keys [id data]}]
  (-> data
      (update :shapes conj id)
      (update :shapes-by-id assoc id data)))

(defn- process-mod-shape
  [data {:keys [id attr value]}]
  (update-in data [:shapes-by-id id] assoc attr value))

(defn- process-del-shape
  [data {:keys [id attr value]}]
  (-> data
      (update :shapes (fn [s] (filterv #(not= % id) s)))
      (update :shapes-by-id dissoc id)))

