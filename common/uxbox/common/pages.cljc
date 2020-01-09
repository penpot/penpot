(ns uxbox.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.data :as d]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::type keyword?)

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
(s/def ::type #{:rect :path :circle :image :text :canvas})
(s/def ::x number?)
(s/def ::y number?)
(s/def ::cx number?)
(s/def ::cy number?)
(s/def ::width number?)
(s/def ::height number?)

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
                   ;; ::page-id ??
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

(s/def ::shapes-by-id
  (s/map-of uuid? ::shape))

(s/def ::data
  (s/keys :req-un [::shapes
                   ::canvas
                   ::options
                   ::shapes-by-id]))

;; Changes related
(s/def ::attr-change
  (s/tuple #{:set} keyword? any?))

(s/def ::change
  (s/or :mod-shape (s/cat :name #(= % :mod-shape)
                          :id uuid?
                          :changes (s/* ::attr-change))
        :add-shape (s/cat :name #(= % :add-shape)
                          :id uuid?
                          :data any?)

        :mod-opts  (s/cat :name #(= % :mod-opts)
                          :changes (s/* ::attr-change))

        :del-shape (s/cat :name #(= % :del-shape)
                          :id uuid?)
        :mov-shape (s/cat :name #(= % :mov-shape)
                          :id1 uuid?
                          :pos #(= :after %)
                          :id2 (s/nilable uuid?))
        :add-canvas (s/cat :name #(= % :add-canvas)
                           :id uuid?
                           :data any?)
        :del-canvas (s/cat :name #(= % :del-canvas)
                           :id uuid?)))

(s/def ::changes
  (s/coll-of ::change :kind vector?))

;; --- Changes Processing Impl

(declare process-change)
(declare process-mod-shape)
(declare process-mod-opts)
(declare process-mov-shape)
(declare process-add-shape)
(declare process-add-canvas)
(declare process-del-shape)
(declare process-del-canvas)

(defn process-changes
  [data items]
  (->> (s/assert ::changes items)
       (reduce process-change data)))

(defn- process-change
  [data [op & rest]]
  (case op
    :mod-shape (process-mod-shape data rest)
    :mov-shape (process-mov-shape data rest)
    :add-shape (process-add-shape data rest)
    :add-canvas (process-add-canvas data rest)
    :del-shape (process-del-shape data rest)
    :del-canvas (process-del-canvas data rest)
    :mod-opts (process-mod-opts data rest)))

(defn- process-mod-shape
  [data [id & changes]]
  (if (get-in data [:shapes-by-id id])
    (update-in data [:shapes-by-id id]
               #(reduce (fn [shape [_ att val]]
                          (if (nil? val)
                            (dissoc shape att)
                            (assoc shape att val)))
                        % changes))
    data))

(defn- process-mod-opts
  [data changes]
  (update data :options
          #(reduce (fn [options [_ att val]]
                     (if (nil? val)
                       (dissoc options att)
                       (assoc options att val)))
                   % changes)))

(defn- process-add-shape
  [data [id sdata]]
  (-> data
      (update :shapes (fn [shapes]
                        (if (some #{id} shapes)
                          shapes
                          (conj shapes id))))
      (update :shapes-by-id assoc id sdata)))

(defn- process-mov-shape
  [data [id _ id2]]
  (let [shapes (:shapes data)
        shapes' (into [] (remove #(= % id) shapes))
        index (d/index-of shapes' id2)]
    (cond
      (= id id2)
      (assoc data :shapes shapes)

      (nil? index)
      (assoc data :shapes (d/concat [id] shapes'))

      :else
      (let [[before after] (split-at (inc index) shapes')]
        (assoc data :shapes (d/concat [] before [id] after))))))

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

