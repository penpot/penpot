;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.history
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.util.router :as r]
            [uxbox.main.repo :as rp]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.forms :as sc]
            [uxbox.main.data.pages :as udp]
            [uxbox.store :as st]
            [uxbox.util.time :as dt]
            [uxbox.util.data :refer (without-keys
                                     replace-by-id
                                     index-by)]))

;; --- Watch Page Changes

(declare fetch-page-history)
(declare fetch-pinned-page-history)

(deftype WatchPageChanges [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [stopper (->> stream
                       (rx/filter #(= % ::udp/stop-page-watcher))
                       (rx/take 1))]
      (->> stream
           (rx/take-until stopper)
           (rx/filter udp/page-persisted?)
           (rx/debounce 500)
           (rx/flat-map #(rx/of (fetch-page-history id)
                                (fetch-pinned-page-history id)))))))

(defn watch-page-changes
  [id]
  (WatchPageChanges. id))

;; --- Pinned Page History Fetched

(declare update-history-index)

(defrecord PinnedPageHistoryFetched [history]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (assoc-in [:workspace :history :pinned-items] (mapv :version history))
        (update-history-index history true))))

;; --- Fetch Pinned Page History

(defrecord FetchPinnedPageHistory [id]
  ptk/WatchEvent
  (watch [_ state s]
    (letfn [(on-success [{history :payload}]
              (->PinnedPageHistoryFetched (into [] history)))]
      (let [params {:page id :pinned true}]
        (->> (rp/req :fetch/page-history params)
             (rx/map on-success))))))

(defn fetch-pinned-page-history
  [id]
  (->FetchPinnedPageHistory id))

;; --- Page History Fetched

(defrecord PageHistoryFetched [history append?]
  ptk/UpdateEvent
  (update [_ state]
    (letfn [(update-counters [state items]
              (-> (assoc state :min-version (apply min items))
                  (assoc :max-version (apply max items))))

            (update-lists [state items]
              (if append?
                (update state :items #(reduce conj %1 items))
                (assoc state :items items)))]

      (let [items (mapv :version history)
            hstate (-> (get-in state [:workspace :history] {})
                       (update-counters items)
                       (update-lists items))]
        (-> state
            (assoc-in [:workspace :history] hstate)
            (update-history-index history append?))))))

;; --- Fetch Page History

(defrecord FetchPageHistory [id since max]
  ptk/WatchEvent
  (watch [_ state s]
    (letfn [(on-success [{history :payload}]
              (let [history (into [] history)]
                (->PageHistoryFetched history (not (nil? since)))))]
      (let [params (merge
                    {:page id :max (or max 15)}
                    (when since {:since since}))]
        (->> (rp/req :fetch/page-history params)
             (rx/map on-success))))))

(defn fetch-page-history
  ([id]
   (fetch-page-history id nil))
  ([id params]
   (map->FetchPageHistory (assoc params :id id))))

;; --- Select Page History

(defrecord SelectPageHistory [version]
  ptk/UpdateEvent
  (update [_ state]
    (let [item (get-in state [:workspace :history :by-version version])
          page (get-in state [:pages (:page item)])
          page (assoc page
                      :history true
                      :data (:data item))]
      (-> state
          (udp/assoc-page page)
          (assoc-in [:workspace :history :selected] version)))))

(defn select-page-history
  [version]
  (SelectPageHistory. version))

;; --- Apply selected history

(defrecord ApplySelectedHistory [id]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (update-in [:pages id] dissoc :history)
        (assoc-in [:workspace :history :selected] nil)))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (udp/persist-page id))))

(defn apply-selected-history
  [id]
  (ApplySelectedHistory. id))

;; --- Deselect Page History

(defrecord DeselectPageHistory [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [packed (get-in state [:packed-pages id])]
      (-> (udp/assoc-page state packed)
          (assoc-in [:workspace :history :deselecting] true)
          (assoc-in [:workspace :history :selected] nil))))

  ptk/WatchEvent
  (watch [_ state s]
    (->> (rx/of #(assoc-in % [:workspace :history :deselecting] false))
         (rx/delay 500))))

(defn deselect-page-history
  [id]
  (DeselectPageHistory. id))

;; --- History Item Updated

(defrecord HistoryItemUpdated [item]
  ptk/UpdateEvent
  (update [_ state]
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
  ptk/WatchEvent
  (watch [_ state s]
    (let [history (get-in state [:workspace :history])
          maxitems (count (:items history))]
      (rx/of (fetch-page-history id {:max maxitems})
             (fetch-pinned-page-history id)))))

(defn refres-page-history
  [id]
  (RefreshPageHistory. id))

;; --- Update History Item

(defrecord UpdateHistoryItem [item]
  ptk/WatchEvent
  (watch [_ state s]
    (letfn [(on-success [{item :payload}]
              (->HistoryItemUpdated item))]
      (rx/merge
       (->> (rp/req :update/page-history item)
            (rx/map on-success))
       (->> (rx/filter history-updated? s)
            (rx/take 1)
            (rx/map #(refres-page-history (:page item))))))))

(defn update-history-item
  [item]
  (UpdateHistoryItem. item))

;; --- Forward to Next Version

(defrecord ForwardToNextVersion []
  ptk/WatchEvent
  (watch [_ state s]
    (let [workspace (:workspace state)
          history (:history workspace)
          version (:selected history)]
      (cond
        (nil? version)
        (rx/empty)

        (>= (:max-version history) (inc version))
        (rx/of (select-page-history (inc version)))

        (> (inc version) (:max-version history))
        (rx/of (deselect-page-history (:page workspace)))

        :else
        (rx/empty)))))

(defn forward-to-next-version
  []
  (ForwardToNextVersion.))

;; --- Backwards to Previous Version

(defrecord BackwardsToPreviousVersion []
  ptk/WatchEvent
  (watch [_ state s]
    (let [workspace (:workspace state)
          history (:history workspace)
          version (:selected history)]
      (cond
        (nil? version)
        (let [maxv (:max-version history)]
          (rx/of (select-page-history maxv)))

        (pos? (dec version))
        (if (contains? (:by-version history) (dec version))
          (rx/of (select-page-history (dec version)))
          (let [since (:min-version history)
                page (:page workspace)
                params {:since since}]
            (rx/of (fetch-page-history page params)
                   (select-page-history (dec version)))))

        :else
        (rx/empty)))))

(defn backwards-to-previous-version
  []
  (BackwardsToPreviousVersion.))

;; --- Helpers

(defn- update-history-index
  [state history append?]
  (let [index (index-by history :version)]
    (if append?
      (update-in state [:workspace :history :by-version] merge index)
      (assoc-in state [:workspace :history :by-version] index))))

