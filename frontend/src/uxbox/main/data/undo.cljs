;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.undo
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.store :as st]))

;; --- Watch Page Changes

(declare save-undo-entry)
(declare save-undo-entry?)
(declare undo?)
(declare redo?)
(declare initialize-undo-for-page)

(deftype WatchPageChanges [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [stopper (->> stream
                       (rx/filter #(= % ::udp/stop-page-watcher))
                       (rx/take 1))]
      (->> stream
           (rx/take-until stopper)
           (rx/filter #(or (satisfies? udp/IPageUpdate %)
                           (satisfies? udp/IMetadataUpdate %)))
           (rx/filter #(not (undo? %)))
           (rx/filter #(not (redo? %)))
           (rx/map #(save-undo-entry id))))))

(defn watch-page-changes
  [id]
  (WatchPageChanges. id))

;; -- Save Undo Entry

(defrecord SaveUndoEntry [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [page (udp/pack-page state id)
          undo {:data (:data page)
                :metadata (:metadata page)}]
      (-> state
          (update-in [:undo id :stack] #(cons undo %))
          (assoc-in [:undo id :selected] 0)))))


(defn save-undo-entry
  [id]
  {:pre [(uuid? id)]}
  (SaveUndoEntry. id))

(defn save-undo-entry?
  [v]
  (instance? SaveUndoEntry v))

;; --- Initialize Undo (For page)

(defrecord InitializeUndoForPage [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [initialized? (get-in state [:undo id])
          page-loaded? (get-in state [:pages id])]
      (cond
        (and page-loaded? initialized?)
        (rx/empty)

        page-loaded?
        (rx/of (save-undo-entry id))

        :else
        (->> stream
             (rx/filter udp/pages-fetched?)
             (rx/take 1)
             (rx/map #(initialize-undo-for-page id)))))))

(defn- initialize-undo-for-page
  [id]
  (InitializeUndoForPage. id))

;; --- Select Previous Entry

(defrecord Undo []
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :page])
          undo-state (get-in state [:undo page-id])
          stack (:stack undo-state)
          selected (:selected undo-state 0)]
      (if (>= selected (dec (count stack)))
        state
        (let [pointer (inc selected)
              page (get-in state [:pages page-id])
              undo (nth stack pointer)
              data (:data undo)
              metadata (:metadata undo)
              packed (assoc page :data data :metadata metadata)]

          ;; (println "Undo: pointer=" pointer)
          ;; (println "Undo: packed=")
          ;; (pp/pprint packed)

          (-> state
              (udp/assoc-page packed)
              (assoc-in [:undo page-id :selected] pointer)))))))

(defn undo
  []
  (Undo.))

(defn undo?
  [v]
  (instance? Undo v))

;; --- Select Next Entry

(defrecord Redo []
  udp/IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (let [page-id (get-in state [:workspace :page])
          undo-state (get-in state [:undo page-id])
          stack (:stack undo-state)
          selected (:selected undo-state)]
      (if (or (nil? selected) (zero? selected))
        state
        (let [pointer (dec selected)
              undo (nth stack pointer)
              data (:data undo)
              metadata (:metadata undo)
              page (get-in state [:pages page-id])
              packed (assoc page :data data :metadata metadata)]

          ;; (println "Redo: pointer=" pointer)
          ;; (println "Redo: packed=")
          ;; (pp/pprint packed)

          (-> state
              (udp/assoc-page packed)
              (assoc-in [:undo page-id :selected] pointer)))))))

(defn redo
  []
  (Redo.))

(defn redo?
  [v]
  (instance? Redo v))
