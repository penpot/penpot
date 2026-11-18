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
   [app.common.types.variant :as ctv]))

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

(defn collect-variant-properties
  "Map of component-id → variant-properties for clipboard copy.
   Variant container: every related component. Variant main: that
   component only. Anything else: empty map."
  [data objects shape]
  (cond
    (ctc/is-variant-container? shape)
    (into {}
          (map (juxt :id :variant-properties))
          (find-variant-components data objects (:id shape)))

    (ctc/is-variant-main? shape)
    (if-let [component (ctcl/get-component data (:component-id shape) true)]
      {(:id component) (:variant-properties component)}
      {})

    :else
    {}))

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
       (ctv/group-properties-by-name)
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

(defn- variant-main-shape-path
  "Path in file data to the main shape of `component`: inside the
   component `:objects` when it is deleted, or on its page. Nil when the
   component has no main."
  [component]
  (let [main-id (:main-instance-id component)
        page-id (:main-instance-page component)]
    (cond
      (and (:deleted component) main-id)
      [:components (:id component) :objects main-id]

      (and page-id main-id)
      [:pages-index page-id :objects main-id]

      :else
      nil)))

(defn get-variant-main-shape
  "Return the main shape map for `component`, or nil when it is absent."
  [data component]
  (some->> (variant-main-shape-path component)
           (get-in data)))

(defn variant-main-label-changed?
  "True when the main shape exists and its :variant-name is not
   `properties-to-name` of `props`."
  [data component props]
  (let [shape (get-variant-main-shape data component)]
    (and (map? shape)
         (not= (ctv/properties-to-name props)
               (:variant-name shape)))))

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

(defn update-variant-properties
  "Apply `f` to the `:variant-properties` of `component` and write the
   result to `data`. Also refresh the `:variant-name` of its main shape
   when it no longer matches. Return `data` unchanged when nothing differs."
  [data component f]
  (let [old-props (vec (or (:variant-properties component) []))
        props     (f old-props)
        data      (if (= old-props props)
                    data
                    (assoc-in data [:components (:id component) :variant-properties] props))]
    (if (variant-main-label-changed? data component props)
      (assoc-in data
                (conj (variant-main-shape-path component) :variant-name)
                (ctv/properties-to-name props))
      data)))

(defn update-variant-container-properties
  "Apply `f` to the property vectors of the members of `container`, in
   `:shapes` order, and write each result with `update-variant-properties`.
   `f` takes and returns a vector of property vectors."
  [data objects container f]
  (let [components (mapv :component (qualifying-variant-members data objects container))
        props-seq  (f (mapv #(vec (or (:variant-properties %) [])) components))]
    (reduce (fn [data [component props]]
              (update-variant-properties data component (constantly props)))
            data
            (map vector components props-seq))))

(defn sanitize-variant-component
  "Sanitize the properties of one variant component on its own."
  [data component]
  (update-variant-properties data component ctv/sanitize-properties))

(defn sanitize-variant-container
  "Sanitize the properties of every member of `container` as one set."
  [data objects container]
  (update-variant-container-properties data objects container ctv/sanitize-properties-set))
