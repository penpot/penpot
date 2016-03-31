;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.data.history
  (:require [cuerdas.core :as str]
            [promesa.core :as p]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.repo :as rp]
            [uxbox.locales :refer (tr)]
            [uxbox.schema :as sc]
            [uxbox.data.pages :as udp]
            [uxbox.state :as st]
            [uxbox.state.project :as stpr]
            [uxbox.ui.messages :as uum]
            [uxbox.util.datetime :as dt]
            [uxbox.util.data :refer (without-keys replace-by-id)]))

;; --- Pinned Page History Fetched

(defrecord PinnedPageHistoryFetched [history]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:workspace :history :pinned-items] history)))

;; --- Fetch Pinned Page History

(defrecord FetchPinnedPageHistory [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [{history :payload}]
              (->PinnedPageHistoryFetched (into [] history)))
            (on-failure [e]
              (uum/error (tr "errors.fetch-page-history"))
              (rx/empty))]
      (let [params {:page id :pinned true}]
        (->> (rp/do :fetch/page-history params)
             (rx/map on-success)
             (rx/catch on-failure))))))

(defn fetch-pinned-page-history
  [id]
  (->FetchPinnedPageHistory id))

;; --- Page History Fetched

(defrecord PageHistoryFetched [history append?]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [items (into [] history)
          minv (apply min (map :version history))
          state (assoc-in state [:workspace :history :min-version] minv)]
      (if-not append?
        (assoc-in state [:workspace :history :items] items)
        (update-in state [:workspace :history :items] #(reduce conj % items))))))

;; --- Fetch Page History

(defrecord FetchPageHistory [id since max]
  rs/WatchEvent
  (-apply-watch [this state s]
    (println "FetchPageHistory" this)
    (letfn [(on-success [{history :payload}]
              (let [history (into [] history)]
                (->PageHistoryFetched history (not (nil? since)))))
            (on-failure [e]
              (uum/error (tr "errors.fetch-page-history"))
              (rx/empty))]
      (let [params (merge
                    {:page id :max (or max 15)}
                    (when since {:since since}))]
        (->> (rp/do :fetch/page-history params)
             (rx/map on-success)
             (rx/catch on-failure))))))

(defn fetch-page-history
  ([id]
   (fetch-page-history id nil))
  ([id params]
   (map->FetchPageHistory (assoc params :id id))))

;; --- Clean Page History

(defrecord CleanPageHistory []
  rs/UpdateEvent
  (-apply-update [_ state]
    (println "CleanPageHistory")
    (assoc-in state [:workspace :history] {})))

(defn clean-page-history
  []
  (CleanPageHistory.))

(defn clean-page-history?
  [v]
  (instance? CleanPageHistory v))

;; --- Watch Page Changes

(defrecord WatchPageChanges []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (println "WatchPageChanges")
    (let [stoper (->> (rx/filter clean-page-history? s)
                      (rx/take 1))]
      (->> (rx/filter udp/page-synced? s)
           (rx/take-until stoper)
           (rx/map (comp :id :page))
           (rx/pr-log "watcher:")
           (rx/mapcat #(rx/of
                        (fetch-page-history %)
                        (fetch-pinned-page-history %)))))))

(defn watch-page-changes
  []
  (WatchPageChanges.))

;; --- Select Page History

(defrecord SelectPageHistory [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [page (get-in state [:pages-by-id (:page item)])
          page' (assoc page
                       :history true
                       :data (:data item))]
      (-> state
          (stpr/unpack-page page')
          (assoc-in [:workspace :history :selected] (:id item))))))

(defn select-page-history
  [item]
  (SelectPageHistory. item))

;; --- Apply selected history

(defrecord ApplySelectedHistory [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (println "ApplySelectedHistory" id)
    (-> state
        (update-in [:pages-by-id id] dissoc :history)
        (assoc-in [:workspace :history :selected] nil)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (udp/update-page id))))

(defn apply-selected-history
  [id]
  (ApplySelectedHistory. id))

;; --- Discard Selected History

(defrecord DiscardSelectedHistory [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [packed (get-in state [:pagedata-by-id id])]
      (-> state
          (stpr/unpack-page packed)
          (assoc-in [:workspace :history :selected] nil)))))

(defn discard-selected-history
  [id]
  (DiscardSelectedHistory. id))

;; --- History Item Updated

(defrecord HistoryItemUpdated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (-> state
        (update-in [:workspace :history :items] replace-by-id item)
        (update-in [:workspace :history :pinned-items] replace-by-id item))))

(defn history-updated?
  [item]
  (instance? HistoryItemUpdated item))

(defn history-updated
  [item]
  (HistoryItemUpdated. item))

;; --- Refresh Page History

(defrecord RefreshPageHistory [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [history (get-in state [:workspace :history])
          maxitems (count (:items history))]
      (rx/of (fetch-page-history id {:max maxitems})
             (fetch-pinned-page-history id)))))

(defn refres-page-history
  [id]
  (RefreshPageHistory. id))

;; --- Update History Item

(defrecord UpdateHistoryItem [item]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [{item :payload}]
              (->HistoryItemUpdated item))
            (on-failure [e]
              (uum/error (tr "errors.page-history-update"))
              (rx/empty))]
      (rx/merge
       (->> (rp/do :update/page-history item)
            (rx/map on-success)
            (rx/catch on-failure))
       (->> (rx/filter history-updated? s)
            (rx/take 1)
            (rx/map #(refres-page-history (:page item))))))))

(defn update-history-item
  [item]
  (UpdateHistoryItem. item))
