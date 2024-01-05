;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.undo
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cpc]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def discard-transaction-time-millis (* 20 1000))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Undo / Redo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  schema:undo-entry
  (sm/define
    [:map {:title "undo-entry"}
     [:undo-changes [:vector ::cpc/change]]
     [:redo-changes [:vector ::cpc/change]]]))

(def check-undo-entry!
  (sm/check-fn schema:undo-entry))

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
      (cond->
       (nil? (get-in state [:workspace-undo :transaction :undo-group]))
        (assoc-in [:workspace-undo :transaction :undo-group] undo-group))
      (assoc-in [:workspace-undo :transaction :tags] tags)))

(defn append-undo
  [entry stack?]
  (dm/assert!
   "expected valid undo entry"
   (check-undo-entry! entry))

  (dm/assert!
   (boolean? stack?))

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

(declare check-open-transactions)

(defn start-undo-transaction
  "Start a transaction, so that every changes inside are added together in a single undo entry."
  [id]
  (ptk/reify ::start-undo-transaction
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rx/of (check-open-transactions))
           ;; Wait the configured time
           (rx/delay discard-transaction-time-millis)))

    ptk/UpdateEvent
    (update [_ state]
      (log/info :msg "start-undo-transaction")
      ;; We commit the old transaction before starting the new one
      (let [current-tx    (get-in state [:workspace-undo :transaction])
            pending-tx    (get-in state [:workspace-undo :transactions-pending])]
        (cond-> state
          (nil? current-tx)  (assoc-in [:workspace-undo :transaction] empty-tx)
          (nil? pending-tx)  (assoc-in [:workspace-undo :transactions-pending] #{id})
          (some? pending-tx) (update-in [:workspace-undo :transactions-pending] conj id)
          :always            (update-in [:workspace-undo :transactions-pending-ts] assoc id (dt/now)))))))

(defn discard-undo-transaction []
  (ptk/reify ::discard-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (log/info :msg "discard-undo-transaction")
      (update state :workspace-undo dissoc :transaction :transactions-pending :transactions-pending-ts))))

(defn commit-undo-transaction [id]
  (ptk/reify ::commit-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (log/info :msg "commit-undo-transaction")
      (let [state (-> state
                      (update-in [:workspace-undo :transactions-pending] disj id)
                      (update-in [:workspace-undo :transactions-pending-ts] dissoc id))]
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

(defn check-open-transactions
  []
  (ptk/reify ::check-open-transactions
    ptk/WatchEvent
    (watch [_ state _]
      (log/info :msg "check-open-transactions")
      (let [pending-ts (-> (dm/get-in state [:workspace-undo :transactions-pending-ts])
                           (update-vals #(.toMillis (dt/diff (dt/now) %))))]
        (->> pending-ts
             (filter (fn [[_ ts]] (>= ts discard-transaction-time-millis)))
             (rx/from)
             (rx/tap #(js/console.warn (dm/str "FORCE COMMIT TRANSACTION AFTER " (second %) "MS")))
             (rx/map first)
             (rx/map commit-undo-transaction))))))
