;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.undo
  (:require
   [app.common.pages.spec :as spec]
   [app.common.spec :as us]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Undo / Redo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::undo-changes ::spec/changes)
(s/def ::redo-changes ::spec/changes)
(s/def ::undo-entry
  (s/keys :req-un [::undo-changes ::redo-changes]))

(def MAX-UNDO-SIZE 50)

(defn- conj-undo-entry
  [undo data]
  (let [undo (conj undo data)
        cnt  (count undo)]
    (if (> cnt MAX-UNDO-SIZE)
      (subvec undo (- cnt MAX-UNDO-SIZE))
      undo)))

;; TODO: Review the necessity of this method
(defn materialize-undo
  [_changes index]
  (ptk/reify ::materialize-undo
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-undo :index] index)))))

(defn- add-undo-entry
  [state entry]
  (if (and entry
           (not-empty (:undo-changes entry))
           (not-empty (:redo-changes entry)))
    (let [index (get-in state [:workspace-undo :index] -1)
          items (get-in state [:workspace-undo :items] [])
          items (->> items (take (inc index)) (into []))
          items (conj-undo-entry items entry)]
      (-> state
          (update :workspace-undo assoc :items items
                                        :index (min (inc index)
                                                    (dec MAX-UNDO-SIZE)))))
    state))

(defn- accumulate-undo-entry
  [state {:keys [undo-changes redo-changes]}]
  (-> state
      (update-in [:workspace-undo :transaction :undo-changes] #(into undo-changes %))
      (update-in [:workspace-undo :transaction :redo-changes] #(into % redo-changes))))

(defn append-undo
  [entry]
  (us/assert ::undo-entry entry)
  (ptk/reify ::append-undo
    ptk/UpdateEvent
    (update [_ state]
      (if (get-in state [:workspace-undo :transaction])
        (accumulate-undo-entry state entry)
        (add-undo-entry state entry)))))

(def empty-tx
  {:undo-changes [] :redo-changes []})

(defn start-undo-transaction []
  (ptk/reify ::start-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      ;; We commit the old transaction before starting the new one
      (let [current-tx (get-in state [:workspace-undo :transaction])]
        (cond-> state
          (nil? current-tx) (assoc-in [:workspace-undo :transaction] empty-tx))))))

(defn discard-undo-transaction []
  (ptk/reify ::discard-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-undo dissoc :transaction))))

(defn commit-undo-transaction []
  (ptk/reify ::commit-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (add-undo-entry (get-in state [:workspace-undo :transaction]))
          (update :workspace-undo dissoc :transaction)))))

(def pop-undo-into-transaction
  (ptk/reify ::last-undo-into-transaction
    ptk/UpdateEvent
    (update [_ state]
      (let [index (get-in state [:workspace-undo :index] -1)]

        (cond-> state
          (>= index 0) (accumulate-undo-entry (get-in state [:workspace-undo :items index]))
          (>= index 0) (update-in [:workspace-undo :index] dec))))))

(def reinitialize-undo
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-undo {}))))

