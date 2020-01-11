(ns uxbox.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.data :as d]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::shape-id uuid?)
(s/def ::session-id uuid?)
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
(s/def ::operation (s/tuple #{:set} keyword? any?))
(s/def ::move-after-id (s/nilable uuid?))

(s/def ::operations
  (s/coll-of ::operation :kind vector?))

(defmulti change-spec-impl :type)

(defmethod change-spec-impl :add-shape [_]
  (s/keys :req-un [::shape ::id ::session-id]))

(defmethod change-spec-impl :add-canvas [_]
  (s/keys :req-un [::shape ::id ::session-id]))

(defmethod change-spec-impl :mod-shape [_]
  (s/keys :req-un [::id ::operations ::session-id]))

(defmethod change-spec-impl :mov-shape [_]
  (s/keys :req-un [::id ::move-after-id ::session-id]))

(defmethod change-spec-impl :mod-opts [_]
  (s/keys :req-un [::operations ::session-id]))

(defmethod change-spec-impl :del-shape [_]
  (s/keys :req-un [::id ::session-id]))

(defmethod change-spec-impl :del-canvas [_]
  (s/keys :req-un [::id ::session-id]))

(s/def ::change (s/multi-spec change-spec-impl :type))
(s/def ::changes (s/coll-of ::change))

;; --- Changes Processing Impl

(defn change
  [data]
  (s/assert ::change data))

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
  [data {:keys [type] :as change}]
  (case type
    :add-shape (process-add-shape data change)
    :add-canvas (process-add-canvas data change)
    :mod-shape (process-mod-shape data change)
    :mov-shape (process-mov-shape data change)
    :del-shape (process-del-shape data change)
    :del-canvas (process-del-canvas data change)
    :mod-opts (process-mod-opts data change)))

(defn- process-add-shape
  [data {:keys [id shape] :as change}]
  (-> data
      (update :shapes (fn [shapes]
                        (if (some #{id} shapes)
                          shapes
                          (conj shapes id))))
      (update :shapes-by-id assoc id shape)))

(defn- process-add-canvas
  [data {:keys [id shape] :as change}]
  (-> data
      (update :canvas (fn [shapes]
                        (if (some #{id} shapes)
                          shapes
                          (conj shapes id))))
      (update :shapes-by-id assoc id shape)))

(defn- process-mod-shape
  [data {:keys [id operations] :as change}]
  (if (get-in data [:shapes-by-id id])
    (update-in data [:shapes-by-id id]
               #(reduce (fn [shape [_ att val]]
                          (if (nil? val)
                            (dissoc shape att)
                            (assoc shape att val)))
                        % operations))
    data))

(defn- process-mod-opts
  [data {:keys [operations]}]
  (update data :options
          #(reduce (fn [options [_ att val]]
                     (if (nil? val)
                       (dissoc options att)
                       (assoc options att val)))
                   % operations)))

(defn- process-mov-shape
  [data {:keys [id move-after-id]}]
  (let [shapes (:shapes data)
        shapes' (into [] (remove #(= % id) shapes))
        index (d/index-of shapes' move-after-id)]
    (cond
      (= id move-after-id)
      (assoc data :shapes shapes)

      (nil? index)
      (assoc data :shapes (d/concat [id] shapes'))

      :else
      (let [[before after] (split-at (inc index) shapes')]
        (assoc data :shapes (d/concat [] before [id] after))))))

(defn- process-del-shape
  [data {:keys [id] :as change}]
  (-> data
      (update :shapes (fn [s] (filterv #(not= % id) s)))
      (update :shapes-by-id dissoc id)))

(defn- process-del-canvas
  [data {:keys [id] :as change}]
  (-> data
      (update :canvas (fn [s] (filterv #(not= % id) s)))
      (update :shapes-by-id dissoc id)))

