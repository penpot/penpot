;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.history
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.repo :as rp]
            [uxbox.util.data :refer [replace-by-id
                                     index-by]]))

;; --- Initialize History State

(declare fetch-history)
(declare fetch-pinned-history)

(deftype Initialize [page-id]
  ptk/UpdateEvent
  (update [_ state]
    (let [data {:section :main
                :selected nil
                :pinned #{}
                :items #{}
                :byver {}}]
      (assoc-in state [:workspace :history] data)))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [page-id (get-in state [:workspace :page])
          stopper (->> stream
                       (rx/filter #(= % ::stop-changes-watcher))
                       (rx/take 1))]
      (rx/merge
       (->> stream
            (rx/take-until stopper)
            (rx/filter udp/page-persisted?)
            (rx/flat-map #(rx/of (fetch-history page-id)
                                 (fetch-pinned-history page-id))))
       (rx/of (fetch-history page-id)
              (fetch-pinned-history page-id))))))

(defn initialize
  [page-id]
  {:pre [(uuid? page-id)]}
  (Initialize. page-id))

;; --- Pinned Page History Fetched

(deftype PinnedPageHistoryFetched [items]
  ptk/UpdateEvent
  (update [_ state]
    (let [items-map (index-by items :version)
          items-set (into #{} items)]
      (update-in state [:workspace :history]
                 (fn [history]
                   (-> history
                       (assoc :pinned items-set)
                       (update :byver merge items-map)))))))

(defn pinned-page-history-fetched
  [items]
  (PinnedPageHistoryFetched. items))

;; --- Fetch Pinned Page History

(deftype FetchPinnedPageHistory [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [params {:page id :pinned true}]
      (->> (rp/req :fetch/page-history params)
           (rx/map :payload)
           (rx/map pinned-page-history-fetched)))))

(defn fetch-pinned-history
  [id]
  {:pre [(uuid? id)]}
  (FetchPinnedPageHistory. id))

;; --- Page History Fetched

(deftype PageHistoryFetched [items]
  ptk/UpdateEvent
  (update [_ state]
    (let [versions (into #{} (map :version) items)
          items-map (index-by items :version)
          min-version (apply min versions)
          max-version (apply max versions)]
      (update-in state [:workspace :history]
                 (fn [history]
                   (-> history
                       (assoc :min-version min-version)
                       (assoc :max-version max-version)
                       (update :byver merge items-map)
                       (update :items #(reduce conj % items))))))))

;; TODO: add spec to history items

(defn page-history-fetched
  [items]
  (PageHistoryFetched. items))

;; --- Fetch Page History

(deftype FetchPageHistory [page-id since max]
  ptk/WatchEvent
  (watch [_ state s]
    (let [params (merge {:page page-id
                         :max (or max 15)}
                        (when since
                          {:since since}))]
      (->> (rp/req :fetch/page-history params)
           (rx/map :payload)
           (rx/map page-history-fetched)))))

(defn fetch-history
  ([id]
   (fetch-history id nil))
  ([id {:keys [since max]}]
   {:pre [(uuid? id)]}
   (FetchPageHistory. id since max)))

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

(deftype LoadMore []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page-id (get-in state [:workspace :page])
          since (get-in state [:workspace :history :min-version])]
      (rx/of (fetch-history page-id {:since since})))))

(defn load-more
  []
  (LoadMore.))

;; --- Select Page History

(deftype SelectPageHistory [version]
  ptk/UpdateEvent
  (update [_ state]
    (let [history (get-in state [:workspace :history])
          item (get-in history [:byver version])
          page (get-in state [:pages (:page item)])

          page (-> (get-in state [:pages (:page item)])
                   (assoc :history true
                          :data (:data item)))]
      (-> state
          (udp/assoc-page page)
          (assoc-in [:workspace :history :selected] version)))))

(defn select-page-history
  [version]
  {:pre [(integer? version)]}
  (SelectPageHistory. version))

;; --- Apply Selected History

(deftype ApplySelectedHistory []
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :page])]
      (-> state
          (update-in [:pages page-id] dissoc :history)
          (assoc-in [:workspace :history :selected] nil))))

  ptk/WatchEvent
  (watch [_ state s]
    (let [page-id (get-in state [:workspace :page])]
      (rx/of (udp/persist-page page-id)))))

(defn apply-selected-history
  []
  (ApplySelectedHistory.))

;; --- Deselect Page History

(deftype DeselectPageHistory [^:mutable noop]
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :page])
          selected (get-in state [:workspace :history :selected])]
      (if (nil? selected)
        (do
          (set! noop true)
          state)
        (let [packed (get-in state [:packed-pages page-id])]
          (-> (udp/assoc-page state packed)
              (assoc-in [:workspace :history :deselecting] true)
              (assoc-in [:workspace :history :selected] nil))))))

  ptk/WatchEvent
  (watch [_ state s]
    (if noop
      (rx/empty)
      (->> (rx/of #(assoc-in % [:workspace :history :deselecting] false))
           (rx/delay 500)))))

(defn deselect-page-history
  []
  (DeselectPageHistory. false))

  ;; --- Refresh Page History

(deftype RefreshHistory []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page-id (get-in state [:workspace :page])
          history (get-in state [:workspace :history])
          maxitems (count (:items history))]
      (rx/of (fetch-history page-id {:max maxitems})
             (fetch-pinned-history page-id)))))

(defn refres-history
  []
  (RefreshHistory.))

;; --- History Item Updated

(deftype HistoryItemUpdated [item]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :history]
               (fn [history]
                 (-> history
                     (update :items #(into #{} (replace-by-id item) %))
                     (update :pinned #(into #{} (replace-by-id item) %))
                     (assoc-in [:byver (:id item)] item))))))

(defn history-updated?
  [item]
  (instance? HistoryItemUpdated item))

(defn history-updated
  [item]
  (HistoryItemUpdated. item))

;; --- Update History Item

(deftype UpdateHistoryItem [item]
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/concat
     (->> (rp/req :update/page-history item)
          (rx/map :payload)
          (rx/map history-updated))
     (->> (rx/filter history-updated? stream)
          (rx/take 1)
          (rx/map refres-history)))))

(defn update-history-item
  [item]
  (UpdateHistoryItem. item))

;; --- Forward to Next Version

(deftype ForwardToNextVersion []
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
        (rx/of (deselect-page-history))

        :else
        (rx/empty)))))

(defn forward-to-next-version
  []
  (ForwardToNextVersion.))

;; --- Backwards to Previous Version

(deftype BackwardsToPreviousVersion []
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
            (rx/of (fetch-history page params)
                   (select-page-history (dec version)))))

        :else
        (rx/empty)))))

(defn backwards-to-previous-version
  []
  (BackwardsToPreviousVersion.))
