(ns uxbox.main.data.library
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.common.data :as d]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.webapi :as wapi]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as r]
   [uxbox.common.uuid :as uuid]))

;; Retrieve libraries

(declare retrieve-libraries-result)

(defn retrieve-libraries
  ([section]
   (retrieve-libraries section uuid/zero))
  ([section team-id]
   (s/assert ::us/uuid team-id)
   (let [method (case section
                  :icons :icon-libraries
                  :images :image-libraries
                  :palettes :color-libraries)]
     (ptk/reify ::retrieve-libraries
       ptk/WatchEvent
       (watch [_ state stream]
         (->> (rp/query! method {:team-id team-id})
              (rx/map (partial retrieve-libraries-result section team-id))))))))

(defn- retrieve-libraries-result
  [section team-id result]
  (ptk/reify ::retrieve-libraries-result
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:library section team-id] result))))

;; Retrieve library data

(declare retrieve-library-data-result)

(defn retrieve-library-data
  [section library-id]
  (ptk/reify ::retrieve-library-data
    ptk/WatchEvent
    (watch [_ state stream]
      (let [method (case section
                     :icons :icons
                     :images :images
                     :palettes :colors)]
        (->> (rp/query! method {:library-id library-id})
             (rx/map (partial retrieve-library-data-result section library-id)))))))

(defn retrieve-library-data-result
  [section library-id data]
  (ptk/reify ::retrieve-library-data-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:library-items section library-id] data)))))


;; Create library

(declare create-library-result)

(defn create-library
  [section team-id name]
  (ptk/reify ::create-library
    ptk/WatchEvent
    (watch [_ state stream]
      (let [method (case section
                    :icons :create-icon-library
                    :images :create-image-library
                    :palettes :create-color-library)]
        (->> (rp/mutation! method {:team-id team-id
                                   :name name})
             (rx/map (partial create-library-result section team-id)))))))

(defn create-library-result
  [section team-id result]
  (ptk/reify ::create-library-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:library section team-id] #(into [result] %))))))

;; Rename library

(declare rename-library-result)

(defn rename-library
  [section team-id library-id name]
  (ptk/reify ::rename-library
    ptk/WatchEvent
    (watch [_ state stream]
      (let [method (case section
                     :icons :rename-icon-library
                     :images :rename-image-library
                     :palettes :rename-color-library)]
        (->> (rp/mutation! method {:id library-id
                                   :name name})
             (rx/map #(rename-library-result section team-id library-id name)))))))

(defn rename-library-result
  [section team-id library-id name]
  (ptk/reify ::rename-library-result
    ptk/UpdateEvent
    (update [_ state]
      (letfn [(change-name
                [library] (if (= library-id (:id library))
                            (assoc library :name name)
                            library))
              (update-fn [libraries] (map change-name libraries))]

        (-> state
            (update-in [:library section team-id] update-fn))))))

;; Delete library

(declare delete-library-result)

(defn delete-library
  [section team-id library-id]
  (ptk/reify ::delete-library
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:library :last-deleted-library] library-id)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [method (case section
                     :icons :delete-icon-library
                     :images :delete-image-library
                     :palettes :delete-color-library)]
        (->> (rp/mutation! method {:id library-id})
             (rx/map #(delete-library-result section team-id library-id)))))))

(defn delete-library-result
  [section team-id library-id]
  (ptk/reify ::create-library-result
    ptk/UpdateEvent
    (update [_ state]
      (let [update-fn (fn [libraries]
                        (filterv #(not= library-id (:id %)) libraries))]
        (-> state
            (update-in [:library section team-id] update-fn))))))

;; Delete library item

(declare delete-item-result)

(defn delete-item
  [section library-id item-id]
  (ptk/reify ::delete-item
    ptk/WatchEvent
    (watch [_ state stream]
      (let [method (case section
                     :icons :delete-icon
                     :images :delete-image
                     :palettes :delete-color)]
        (->> (rp/mutation! method {:id item-id})
             (rx/map #(delete-item-result section library-id item-id)))))))

(defn delete-item-result
  [section library-id item-id]
  (ptk/reify ::delete-item-result
    ptk/UpdateEvent
    (update [_ state]
      (let [update-fn (fn [items]
                        (filterv #(not= item-id (:id %)) items))]
        (-> state
            (update-in [:library-items section library-id] update-fn))))))

;; Batch delete

(declare batch-delete-item-result)

(defn batch-delete-item
  [section library-id item-ids]
  (ptk/reify ::batch-delete-item
    ptk/WatchEvent
    (watch [_ state stream]
      (let [method (case section
                     :icons :delete-icon
                     :images :delete-image
                     :palettes :delete-color)]
        (->> (rx/from item-ids)
             (rx/flat-map #(rp/mutation! method {:id %}))
             (rx/last)
             (rx/map #(batch-delete-item-result section library-id item-ids)))))))

(defn batch-delete-item-result
  [section library-id item-ids]
  (ptk/reify ::batch-delete-item-result
    ptk/UpdateEvent
    (update [_ state]
      (let [item-ids-set (set item-ids)
            update-fn (fn [items]
                        (filterv #(not (item-ids-set (:id %))) items))]
        (-> state
            (update-in [:library-items section library-id] update-fn))))))

;; Workspace - select library

(defn select-library
  [section library-id]
  (ptk/reify ::select-library
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:library-selected section] library-id)))))


;; Workspace - change library filter

(defn change-library-filter
  [section filter]
  (ptk/reify ::change-library-filter
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:library-filter section] filter)))))
