;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.history
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.repo :as rp]
   [uxbox.util.data :refer [replace-by-id index-by]]
   [uxbox.util.spec :as us]))

;; --- Schema

(s/def ::pinned ::us/bool)
(s/def ::id ::us/uuid)
(s/def ::label ::us/string)
(s/def ::project ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::version ::us/number)
(s/def ::user ::us/uuid)

(s/def ::shapes
  (s/every ::udp/minimal-shape :kind vector?))

(s/def ::data
  (s/keys :req-un [::shapes]))

(s/def ::history-entry
  (s/keys :req-un [::id
                   ::pinned
                   ::label
                   ::project
                   ::created-at
                   ::modified-at
                   ::version
                   ::user
                   ::data]))

(s/def ::history-entries
  (s/every ::history-entry))

;; --- Initialize History State

(declare fetch-history)
(declare fetch-pinned-history)

(defn initialize
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace id]
                 assoc :history {:selected nil
                                 :pinned #{}
                                 :items #{}
                                 :byver {}}))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (fetch-history id)
             (fetch-pinned-history id)))))

;; --- Watch Page Changes

(defn watch-page-changes
  [id]
  (s/assert ::us/uuid id)
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/filter #(= % ::stop-page-watcher) stream)]
        (->> stream
             (rx/filter udp/page-persisted?)
             (rx/debounce 1000)
             (rx/flat-map #(rx/of (fetch-history id)
                                  (fetch-pinned-history id)))
             (rx/take-until stopper))))))

;; --- Pinned Page History Fetched

(defn pinned-history-fetched
  [items]
  (s/assert ::history-entries items)
  (ptk/reify ::pinned-history-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            items-map (index-by :version items)
            items-set (into #{} items)]
        (update-in state [:workspace pid :history]
                   (fn [history]
                     (-> history
                         (assoc :pinned items-set)
                         (update :byver merge items-map))))))))

;; --- Fetch Pinned Page History

(defn fetch-pinned-history
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::fetch-pinned-history
    ptk/WatchEvent
    (watch [_ state s]
      (let [params {:page id :pinned true}]
        (->> (rp/req :fetch/page-history params)
             (rx/map :payload)
             (rx/map pinned-history-fetched))))))

;; --- Page History Fetched

(defn history-fetched
  [items]
  (s/assert ::history-entries items)
  (ptk/reify ::history-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            versions (into #{} (map :version) items)
            items-map (index-by :version items)
            min-version (apply min versions)
            max-version (apply max versions)]
        (update-in state [:workspace pid :history]
                   (fn [history]
                     (-> history
                         (assoc :min-version min-version)
                         (assoc :max-version max-version)
                         (update :byver merge items-map)
                         (update :items #(reduce conj % items)))))))))

;; --- Fetch Page History

(defn fetch-history
  ([id]
   (fetch-history id nil))
  ([id {:keys [since max]}]
   (s/assert ::us/uuid id)
   (ptk/reify ::fetch-history
     ptk/WatchEvent
     (watch [_ state s]
       (let [params (merge {:page id
                            :max (or max 5)}
                           (when since
                             {:since since}))]
         (->> (rp/req :fetch/page-history params)
              (rx/map :payload)
              (rx/map history-fetched)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context Aware Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Select Section

(deftype SelectSection [section]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :history :section] section)))

(defn select-section
  [section]
  {:pre [(keyword? section)]}
  (SelectSection. section))

;; --- Load More

(def load-more
  (ptk/reify ::load-more
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            since (get-in state [:workspace pid :history :min-version])]
        (rx/of (fetch-history pid {:since since}))))))

;; --- Select Page History

(defn select
  [version]
  (s/assert int? version)
  (ptk/reify ::select
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            item (get-in state [:workspace pid :history :byver version])
            page (-> (get-in state [:pages pid])
                     (assoc :history true
                            :data (:data item)))]
        (-> state
            (udp/unpack-page page)
            (assoc-in [:workspace pid :history :selected] version))))))

;; --- Apply Selected History

(def apply-selected
  (ptk/reify ::apply-selected
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])]
        (-> state
            (update-in [:pages pid] dissoc :history)
            (assoc-in [:workspace pid :history :selected] nil))))

    ptk/WatchEvent
    (watch [_ state s]
      (let [pid (get-in state [:workspace :current])]
        (rx/of (udp/persist-page pid))))))

;; --- Deselect Page History

(def deselect
  (ptk/reify ::deselect
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            packed (get-in state [:packed-pages pid])]
        (-> (udp/unpack-page state packed)
            (assoc-in [:workspace pid :history :selected] nil))))))

  ;; --- Refresh Page History

(def refres-history
  (ptk/reify ::refresh-history
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            history (get-in state [:workspace pid :history])
            maxitems (count (:items history))]
        (rx/of (fetch-history pid {:max maxitems})
               (fetch-pinned-history pid))))))

;; --- History Item Updated

(defn history-updated
  [item]
  (s/assert ::history-entry item)
  (ptk/reify ::history-item-updated
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])]
        (update-in state [:workspace pid :history]
                   (fn [history]
                     (-> history
                         (update :items #(into #{} (replace-by-id item) %))
                         (update :pinned #(into #{} (replace-by-id item) %))
                         (assoc-in [:byver (:version item)] item))))))))

(defn history-updated?
  [v]
  (= ::history-item-updated (ptk/type v)))

;; --- Update History Item

(defn update-history-item
  [item]
  (ptk/reify ::update-history-item
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/concat
       (->> (rp/req :update/page-history item)
            (rx/map :payload)
            (rx/map history-updated))
       (->> (rx/filter history-updated? stream)
            (rx/take 1)
            (rx/map (constantly refres-history)))))))
