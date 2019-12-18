(ns uxbox.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::type keyword?)

;; Metadata related
(s/def ::grid-x-axis number?)
(s/def ::grid-y-axis number?)
(s/def ::grid-color string?)
(s/def ::background string?)
(s/def ::background-opacity number?)

;; Page related
(s/def ::file-id uuid?)
(s/def ::user uuid?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)
(s/def ::version number?)
(s/def ::ordering number?)

;; Page Data related
(s/def ::shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def ::shapes (s/coll-of uuid? :kind vector?))
(s/def ::canvas (s/coll-of uuid? :kind vector?))

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

(s/def ::shape-change
  (s/tuple #{:add :mod :del} keyword? any?))

(s/def ::operation
  (s/or :mod-shape (s/cat :name #(= % :mod-shape)
                          :id uuid?
                          :changes (s/* ::shape-change))
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
  (->> (s/assert ::operations operations)
       (reduce process-operation data)))

(defn- process-operation
  [data [op & rest]]
  (case op
    :mod-shape (process-mod-shape data rest)
    :add-shape (process-add-shape data rest)
    :del-shape (process-del-shape data rest)
    :add-canvas (process-add-canvas data rest)
    :del-canvas (process-del-canvas data rest)))

(defn- process-mod-shape
  [data [id & changes]]
  (if (get-in data [:shapes-by-id id])
    (update-in data [:shapes-by-id id]
               #(reduce (fn [shape [op att val]]
                          (if (= op :del)
                            (dissoc shape att)
                            (assoc shape att val)))
                        % changes))
    data))

(defn- process-add-shape
  [data [id sdata]]
  (-> data
      (update :shapes (fn [shapes]
                        (if (some #{id} shapes)
                          shapes
                          (conj shapes id))))
      (update :shapes-by-id assoc id sdata)))

(defn- process-del-shape
  [data [id]]
  (-> data
      (update :shapes (fn [s] (filterv #(not= % id) s)))
      (update :shapes-by-id dissoc id)))

(defn- process-add-canvas
  [data [id sdata]]
  (-> data
      (update :canvas (fn [shapes]
                        (if (some #{id} shapes)
                          shapes
                          (conj shapes id))))
      (update :shapes-by-id assoc id sdata)))

(defn- process-del-canvas
  [data [id]]
  (-> data
      (update :canvas (fn [s] (filterv #(not= % id) s)))
      (update :shapes-by-id dissoc id)))

