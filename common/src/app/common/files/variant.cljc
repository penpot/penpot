;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns app.common.files.variant
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.components-list :as ctkl]
   [app.common.types.variant :as ctv]))

(defn find-variant-components
  "Find the components that belong to the variant container identified by `variant-id`,
   preserving the order defined by the container's shapes.

   Example return:
   (<component1> <component2> ...)"
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
   (assert (or (uuid? variant-id) (nil? variant-id)))
   ;; We can't simply filter components, because we need to maintain the order
   (let [container (get objects variant-id)]
     (if (ctv/variant-container? container)
       (->> (:shapes container)
            (map #(dm/get-in objects [% :component-id]))
            (map #(ctkl/get-component data % true))
            reverse)
       []))))

(defn extract-properties-values
  "Get a map of variant property names to their distinct possible values,
   collected from all components that belong to the variant container.

   Example return:
   [{:name 'Property 1' :value ('Value1' 'Value2')}]"
  [data objects variant-id]
  (assert (or (uuid? variant-id) (nil? variant-id)))
  (->> (find-variant-components data objects variant-id)
       (mapcat :variant-properties)
       (group-by :name)
       (mapv (fn [[k v]]
               (let [mdata (reduce merge {} (map meta v))]
                 (with-meta {:name k
                             :value (->> v (map :value) distinct)}
                   mdata))))))

(defn- get-variant-mains
  "Return the ids of the main instance shapes of the variant this component belongs to,
   in the order they appear in the container.

   Example return:
   [<main-shape-a-id> <main-shape-b-id>]"
  [data component]
  (when-let [variant-id (:variant-id component)]
    (let [page-id (:main-instance-page component)
          objects (-> (dm/get-in data [:pages-index page-id])
                      (get :objects))]
      (dm/get-in objects [variant-id :shapes]))))

(defn is-secondary-variant?
  "Return true if the component is a secondary variant in its variant container.
   The primary variant is the last one in the container's children list.
   Return false if the component is the primary variant or if it's not part of a variant."
  [data component]
  (let [shapes (get-variant-mains data component)]
    (and (seq shapes)
         (not= (:main-instance-id component) (last shapes)))))

(defn get-primary-variant
  "Return the main instance of the primary variant (the last one) in the variant container."
  [data component]
  (let [page-id (:main-instance-page component)
        objects (-> (dm/get-in data [:pages-index page-id])
                      (get :objects))]
    (->> (get-variant-mains data component)
         peek
         (get objects))))
