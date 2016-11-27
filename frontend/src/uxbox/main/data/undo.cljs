;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.undo
  (:require #_[cljs.pprint :as pp]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.data.pages :as udp]
            [uxbox.store :as st]))

;; --- Watch Page Changes

(declare save-undo-entry)
(declare save-undo-entry?)
(declare undo?)
(declare redo?)
(declare initialize-undo-for-page)

(defn watch-page-changes
  "A function that starts watching for `IPageUpdate`
  events emited to the global event stream and just
  reacts on them emiting an other event that just
  persists the state of the page in an undo stack."
  [id]
  (st/emit! (initialize-undo-for-page id))
  (as-> st/store $
    (rx/filter #(satisfies? udp/IPageUpdate %) $)
    (rx/filter #(not (undo? %)) $)
    (rx/filter #(not (redo? %)) $)
    (rx/debounce 500 $)
    (rx/on-next $ #(st/emit! (save-undo-entry id)))))

;; -- Save Undo Entry

(defrecord SaveUndoEntry [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [page (udp/pack-page state id)]
      (-> state
          (update-in [:undo id :stack] #(cons (:data page) %))
          (assoc-in [:undo id :selected] 0)))))

  ;; ptk/EffectEvent
  ;; (effect [_ state]
  ;;   (let [undo (get-in state [:undo id])]
  ;;     (println (pr-str undo)))))

(defn save-undo-entry
  [id]
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
          undo (get-in state [:undo page-id])
          stack (:stack undo)
          selected (:selected undo 0)]
      (if (>= selected (dec (count stack)))
        state
        (let [pointer (inc selected)
              page (get-in state [:pages page-id])
              data (nth stack pointer)
              packed (assoc page :data data)]

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
          undo (get-in state [:undo page-id])
          stack (:stack undo)
          selected (:selected undo)]
      (if (or (nil? selected) (zero? selected))
        state
        (let [pointer (dec selected)
              data (nth stack pointer)
              page (get-in state [:pages page-id])
              packed (assoc page :data data)]

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
