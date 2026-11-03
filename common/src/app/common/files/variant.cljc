;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns app.common.files.variant
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctcl]
   [app.common.types.variant :as ctv]
   [cuerdas.core :as str]))

(defn find-variant-components
  "Find a list of the components that belongs to this variant-id"
  ([data variant-id]
   (let [page-id (->> data
                      :components
                      vals
                      (filter #(= (:variant-id %) variant-id))
                      first
                      :main-instance-page)
         objects (dm/get-in data [:pages-index page-id :objects])]
     (find-variant-components data objects variant-id)))
  ([data objects variant-id]
   ;; We can't simply filter components, because we need to maintain the order
   (->> (dm/get-in objects [variant-id :shapes])
        (map #(dm/get-in objects [% :component-id]))
        (map #(ctcl/get-component data % true))
        reverse)))

(defn extract-properties-names
  [shape data]
  (->> shape
       (#(ctcl/get-component data (:component-id %) true))
       :variant-properties
       (map :name)))

(defn extract-properties-values
  "Get a map of properties associated to their possible values"
  [data objects variant-id]
  (->> (find-variant-components data objects variant-id)
       (mapcat :variant-properties)
       (group-by :name)
       (mapv (fn [[k v]]
               (let [mdata (reduce merge {} (map meta v))]
                 (with-meta {:name k
                             :value (->> v (map :value) distinct)}
                   mdata))))))

(defn get-variant-mains
  [component data]
  (assert (ctv/valid-variant-component? component) "expected valid component variant")
  (when-let [variant-id (:variant-id component)]
    (let [page-id (:main-instance-page component)
          objects (-> (dm/get-in data [:pages-index page-id])
                      (get :objects))]
      (dm/get-in objects [variant-id :shapes]))))

(defn is-secondary-variant?
  [component data]
  (let [shapes  (get-variant-mains component data)]
    (and (seq shapes)
         (not= (:main-instance-id component) (last shapes)))))

(defn get-primary-variant
  [data component]
  (let [page-id    (:main-instance-page component)
        objects    (-> (dm/get-in data [:pages-index page-id])
                       (get :objects))
        variant-id (:variant-id component)]
    (->> (dm/get-in objects [variant-id :shapes])
         peek
         (get objects))))

(defn get-primary-component
  [data component-id]
  (when-let [component (ctcl/get-component data component-id)]
    (if (ctc/is-variant? component)
      (->> component
           (get-primary-variant data)
           :component-id
           (ctcl/get-component data))
      component)))

(def ^:private variant-property-passes 10)

(defn- clip-property-text
  [value pass]
  (let [value (or value "")]
    (cond
      (<= (count value) ctv/property-max-length)
      value

      (>= pass variant-property-passes)
      (subs value 0 ctv/property-max-length)

      :else
      (let [matched (re-matches #"^(.*?)( ?\(\d+\))$" value)
            base    (if matched (nth matched 1) value)
            suffix  (if matched (nth matched 2) "")
            room    (- ctv/property-max-length (count suffix))]
        (if (neg? room)
          (subs value 0 ctv/property-max-length)
          (str (subs base 0 (min (count base) room)) suffix))))))

(defn- trimmed-unique-text
  "Number `text` against `taken`, then trim. An empty base is stored
   as \"(1)\" rather than \" (1)\", which the next sanitize would trim."
  [taken text]
  (loop [candidate (str/trim (or text ""))
         guard     0]
    (let [numbered (str/trim (ctv/update-number-in-repeated-item taken candidate))]
      (cond
        (>= guard 32)
        numbered

        (some #(= numbered %) taken)
        (recur numbered (inc guard))

        :else
        numbered))))

(defn- property-name-or-generated
  [name taken-names]
  (let [text (or (ctv/sanitize-property-text (when (string? name) name)) "")]
    (if (str/blank? text)
      (str ctv/property-prefix
           (ctv/next-property-number (mapv (fn [taken-name] {:name taken-name}) taken-names)))
      text)))

(defn- sanitized-property-value
  [value]
  (let [text (or (ctv/sanitize-property-text (when (string? value) value)) "")]
    (if (> (count text) ctv/property-max-length)
      (subs text 0 ctv/property-max-length)
      text)))

(defn- sanitize-variant-properties
  [props]
  (reduce (fn [acc prop]
            (conj acc (assoc prop
                             :name (property-name-or-generated (:name prop) (mapv :name acc))
                             :value (sanitized-property-value (:value prop)))))
          []
          (or props [])))

(defn- variant-property-names-settled?
  [props]
  (let [names (mapv :name props)]
    (and (= (count names) (count (distinct names)))
         (every? #(<= (count %) ctv/property-max-length) names))))

(defn- settle-variant-property-names
  [props]
  (loop [props (vec props)
         pass  1]
    (let [props' (reduce (fn [acc prop]
                           (let [taken (mapv :name acc)
                                 named (-> (:name prop)
                                           (clip-property-text pass)
                                           (->> (trimmed-unique-text taken))
                                           (clip-property-text pass))
                                 named (if (and (some #(= named %) taken)
                                                (< pass variant-property-passes))
                                         (-> (trimmed-unique-text taken named)
                                             (clip-property-text pass))
                                         named)]
                             (conj acc (assoc prop :name named))))
                         []
                         props)]
      (if (or (variant-property-names-settled? props')
              (>= pass variant-property-passes))
        props'
        (recur props' (inc pass))))))

(defn get-variant-main-shape
  "Return the main shape map for `component`, or nil when it is absent."
  [data component]
  (let [main-id (:main-instance-id component)
        page-id (:main-instance-page component)]
    (cond
      (and (:deleted component) main-id)
      (get-in component [:objects main-id])

      (and page-id main-id)
      (get-in data [:pages-index page-id :objects main-id])

      :else
      nil)))

(defn variant-main-label-changed?
  "True when the main shape exists and its :variant-name is not
   `properties-to-name` of `props`."
  [data component props]
  (let [shape (get-variant-main-shape data component)]
    (and (map? shape)
         (not= (ctv/properties-to-name props)
               (:variant-name shape)))))

(defn prepare-variant-properties
  "Sanitize one property vector that is not part of a live set."
  [props]
  (-> props
      sanitize-variant-properties
      settle-variant-property-names))

(defn- canonical-variant-property-names
  [props-seq]
  (reduce (fn [names props]
            (reduce (fn [names prop]
                      (if (some #(= (:name prop) %) names)
                        names
                        (conj names (:name prop))))
                    names
                    props))
          []
          props-seq))

(defn- align-variant-properties
  [props names]
  (mapv (fn [name]
          (or (some #(when (= name (:name %)) %) props)
              {:name name :value ""}))
        names))

(defn- value-index-to-number
  [props]
  (or (->> (map-indexed vector props)
           (filter (fn [[_ prop]]
                     (not (str/blank? (:value prop)))))
           last
           first)
      0))

(defn- resolve-variant-value-tuple
  [props seen]
  (loop [props (vec props)
         pass  1]
    (let [tuple (mapv :value props)]
      (if (or (empty? props)
              (not (some #(= tuple %) seen))
              (> pass variant-property-passes))
        props
        (let [idx     (value-index-to-number props)
              taken   (mapv #(nth % idx "") seen)
              current (:value (nth props idx))
              updated (if (>= pass variant-property-passes)
                        (let [text (or current "")]
                          (subs text 0 (min ctv/property-max-length (count text))))
                        (-> (trimmed-unique-text taken current)
                            (clip-property-text pass)))]
          (if (>= pass variant-property-passes)
            (assoc-in props [idx :value] updated)
            (recur (assoc-in props [idx :value] updated)
                   (inc pass))))))))

(defn- qualifying-variant-member
  [data objects container child-id]
  (when-let [child (get objects child-id)]
    (let [component (get-in data [:components (:component-id child)])]
      (when (and (ctc/is-variant? child)
                 (= (:id container) (:variant-id child))
                 (some? component)
                 (= (:id container) (:variant-id component)))
        {:component    component
         :component-id (:id component)}))))

(defn qualifying-variant-members
  "Return qualifying members of `container` in `:shapes` order.
   Each member is {:component component :component-id id}."
  [data objects container]
  (into []
        (keep #(qualifying-variant-member data objects container %))
        (:shapes container)))

(defn repair-variant-members
  "Return `members` with finished `:props`.
   Each member is {:component component}. Order is board :shapes order."
  [members]
  (let [prepared (mapv (fn [member]
                         (assoc member :props (prepare-variant-properties
                                               (:variant-properties (:component member)))))
                       members)
        names    (canonical-variant-property-names (map :props prepared))
        aligned  (mapv (fn [member]
                         (update member :props #(align-variant-properties % names)))
                       prepared)]
    (reduce (fn [acc member]
              (let [seen  (mapv #(mapv :value (:props %)) acc)
                    props (resolve-variant-value-tuple (:props member) seen)]
                (conj acc (assoc member :props props))))
            []
            aligned)))
