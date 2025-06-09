;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.common.files.variant
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctcl]
   [app.common.types.variant :as ctv]))


(defn find-variant-components
  "Find a list of the components thet belongs to this variant-id"
  [data objects variant-id]
  ;; We can't simply filter components, because we need to maintain the order
  (->> (dm/get-in objects [variant-id :shapes])
       (map #(dm/get-in objects [% :component-id]))
       (map #(ctcl/get-component data % true))
       reverse))


(defn extract-properties-names
  [shape data]
  (->> shape
       (#(ctcl/get-component data (:component-id %) true))
       :variant-properties
       (map :name)))


(defn extract-properties-values
  [data objects variant-id]
  (->> (find-variant-components data objects variant-id)
       (mapcat :variant-properties)
       (group-by :name)
       (map (fn [[k v]]
              {:name k
               :value (->> v (map :value) distinct)}))))

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
