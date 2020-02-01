;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.undo
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.store :as st]))

(def MAX-STACK-SIZE 50)

;; --- Watch Page Changes

(declare save-undo-entry)
(declare save-undo-entry?)
(declare undo?)
(declare redo?)

(defn watch-page-changes
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::watch-page-changes
    ptk/WatchEvent
    (watch [_ state stream]
      #_(let [stopper (rx/filter #(= % ::dp/stop-page-watcher) stream)]
        (->> stream
             (rx/filter dp/page-update?)
             (rx/filter #(not (undo? %)))
             (rx/filter #(not (redo? %)))
             (rx/map #(save-undo-entry id))
             (rx/take-until stopper))))))

;; -- Save Undo Entry

(defn save-undo-entry
  [id]
  (us/verify ::us/uuid id)
  (letfn [(cons-entry [stack entry]
            (let [stack (cons entry stack)]
              (if (> (count stack) MAX-STACK-SIZE)
                (take MAX-STACK-SIZE stack)
                stack)))]
    (ptk/reify ::save-undo-entry
      ptk/UpdateEvent
      (update [_ state]
        #_(let [page (dp/pack-page state id)
              undo {:data (:data page)
                    :metadata (:metadata page)}]
          (-> state
              (update-in [:undo id :stack] cons-entry undo)
              (assoc-in [:undo id :selected] 0)))))))

(defn save-undo-entry?
  [v]
  (= (ptk/type v) ::save-undo-entry))

;; --- Select Previous Entry

(def undo
  (ptk/reify ::undo
    ptk/UpdateEvent
    (update [_ state]
      #_(let [pid (get-in state [:workspace :current])
            {:keys [stack selected] :as ustate} (get-in state [:undo pid])]
        (if (>= selected (dec (count stack)))
          state
          (let [pointer (inc selected)
                page (get-in state [:pages pid])
                undo (nth stack pointer)
                data (:data undo)
                metadata (:metadata undo)
                packed (assoc page :data data :metadata metadata)]

            ;; (println "Undo: pointer=" pointer)
            ;; (println "Undo: packed=")
            ;; (pp/pprint packed)

            (-> state
                (dp/unpack-page packed)
                (assoc-in [:undo pid :selected] pointer))))))))

(defn undo?
  [v]
  (= (ptk/type v) ::undo))

;; --- Select Next Entry

(def redo
  (ptk/reify ::redo
    ptk/UpdateEvent
    (update [_ state]
      #_(let [pid (get-in state [:workspace :current])
            undo-state (get-in state [:undo pid])
          stack (:stack undo-state)
          selected (:selected undo-state)]
      (if (or (nil? selected) (zero? selected))
        state
        (let [pointer (dec selected)
              undo (nth stack pointer)
              data (:data undo)
              metadata (:metadata undo)
              page (get-in state [:pages pid])
              packed (assoc page :data data :metadata metadata)]

          ;; (println "Redo: pointer=" pointer)
          ;; (println "Redo: packed=")
          ;; (pp/pprint packed)

          (-> state
              (dp/unpack-page packed)
              (assoc-in [:undo pid :selected] pointer))))))))

(defn redo?
  [v]
  (= (ptk/type v) ::redo))
