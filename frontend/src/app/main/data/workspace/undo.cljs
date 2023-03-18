;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.undo
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.changes :as cpc]
   [app.common.schema :as sm]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Undo / Redo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:undo-entry
  [:map
   [:undo-changes [:vector ::cpc/change]]
   [:redo-changes [:vector ::cpc/change]]])

(def undo-entry?
  (sm/pred-fn schema:undo-entry))

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

(defn- stack-undo-entry
  [state {:keys [undo-changes redo-changes] :as entry}]
    (let [index (get-in state [:workspace-undo :index] -1)]
    (if (>= index 0)
      (update-in state [:workspace-undo :items index]
                 (fn [item]
                   (-> item
                       (update :undo-changes #(into undo-changes %))
                       (update :redo-changes #(into % redo-changes)))))
      (add-undo-entry state entry))))

(defn- accumulate-undo-entry
  [state {:keys [undo-changes redo-changes undo-group tags]}]
  (-> state
      (update-in [:workspace-undo :transaction :undo-changes] #(into undo-changes %))
      (update-in [:workspace-undo :transaction :redo-changes] #(into % redo-changes))
      (assoc-in [:workspace-undo :transaction :undo-group] undo-group)
      (assoc-in [:workspace-undo :transaction :tags] tags)))

(defn append-undo
  [entry stack?]
  (dm/assert! (boolean? stack?))
  (dm/assert! (undo-entry? entry))

  (ptk/reify ::append-undo
    ptk/UpdateEvent
    (update [_ state]
     (cond
       (and (get-in state [:workspace-undo :transaction])
            (or (not stack?)
                (d/not-empty? (get-in state [:workspace-undo :transaction :undo-changes]))
                (d/not-empty? (get-in state [:workspace-undo :transaction :redo-changes]))))
       (accumulate-undo-entry state entry)

       stack?
       (stack-undo-entry state entry)

       :else
       (add-undo-entry state entry)))))

(def empty-tx
  {:undo-changes [] :redo-changes []})

(defn start-undo-transaction
  "Start a transaction, so that every changes inside are added together in a single undo entry."
  [id]
  (ptk/reify ::start-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      ;; We commit the old transaction before starting the new one
      (let [current-tx    (get-in state [:workspace-undo :transaction])
            pending-tx    (get-in state [:workspace-undo :transactions-pending])]
        (cond-> state
          (nil? current-tx)  (assoc-in [:workspace-undo :transaction] empty-tx)
          (nil? pending-tx)  (assoc-in [:workspace-undo :transactions-pending] #{id})
          (some? pending-tx) (update-in [:workspace-undo :transactions-pending] conj id))))))

(defn discard-undo-transaction []
  (ptk/reify ::discard-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-undo dissoc :transaction :transactions-pending))))

(defn commit-undo-transaction [id]
  (ptk/reify ::commit-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (let [state (update-in state [:workspace-undo :transactions-pending] disj id)]
        (if (empty? (get-in state [:workspace-undo :transactions-pending]))
          (-> state
              (add-undo-entry (get-in state [:workspace-undo :transaction]))
              (update :workspace-undo dissoc :transaction))
          state)))))

(def reinitialize-undo
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-undo {}))))

