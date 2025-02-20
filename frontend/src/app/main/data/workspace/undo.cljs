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
   [app.common.types.shape.layout :as ctl]
   [app.main.data.changes :as dch]
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(def discard-transaction-time-millis (* 20 1000))

(def ^:private
  schema:undo-entry
  [:map {:title "undo-entry"}
   [:undo-changes [:vector ::cpc/change]]
   [:redo-changes [:vector ::cpc/change]]])

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
      (cond-> (nil? (get-in state [:workspace-undo :transaction :undo-group]))
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

(defn undo-to-index
  "Repeat undoing or redoing until dest-index is reached."
  [dest-index]
  (ptk/reify ::undo-to-index
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when-not (and (or (some? edition) (some? (:object drawing)))
                       (not (ctl/grid-layout? objects edition)))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when (and (some? items)
                       (<= -1 dest-index (dec (count items))))
              (let [changes (vec (apply concat
                                        (cond
                                          (< dest-index index)
                                          (->> (subvec items (inc dest-index) (inc index))
                                               (reverse)
                                               (map :undo-changes))
                                          (> dest-index index)
                                          (->> (subvec items (inc index) (inc dest-index))
                                               (map :redo-changes))
                                          :else [])))]
                (when (seq changes)
                  (rx/of (materialize-undo changes dest-index)
                         (dch/commit-changes {:redo-changes changes
                                              :undo-changes []
                                              :origin it
                                              :save-undo? false})))))))))))

(declare ^:private assure-valid-current-page)

(def undo
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]

        ;; Editors handle their own undo's
        (when (or (and (nil? edition) (nil? (:object drawing)))
                  (ctl/grid-layout? objects edition))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index -1))
              (let [item (get items index)
                    changes (:undo-changes item)
                    undo-group (:undo-group item)

                    find-first-group-idx
                    (fn [index]
                      (if (= (dm/get-in items [index :undo-group]) undo-group)
                        (recur (dec index))
                        (inc index)))

                    undo-group-index
                    (when undo-group
                      (find-first-group-idx index))]

                (if undo-group
                  (rx/of (undo-to-index (dec undo-group-index)))
                  (rx/of (materialize-undo changes (dec index))
                         (dch/commit-changes {:redo-changes changes
                                              :undo-changes []
                                              :save-undo? false
                                              :origin it})
                         (assure-valid-current-page)))))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when (and (or (nil? edition) (ctl/grid-layout? objects edition))
                   (or (empty? drawing) (= :curve (:tool drawing))))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index (dec (count items))))
              (let [item (get items (inc index))
                    changes (:redo-changes item)
                    undo-group (:undo-group item)
                    find-last-group-idx (fn flgidx [index]
                                          (let [item (get items index)]
                                            (if (= (:undo-group item) undo-group)
                                              (flgidx (inc index))
                                              (dec index))))

                    redo-group-index (when undo-group
                                       (find-last-group-idx (inc index)))]
                (if undo-group
                  (rx/of (undo-to-index redo-group-index))
                  (rx/of (materialize-undo changes (inc index))
                         (dch/commit-changes {:redo-changes changes
                                              :undo-changes []
                                              :origin it
                                              :save-undo? false})))))))))))

(defn- assure-valid-current-page
  []
  (ptk/reify ::assure-valid-current-page
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            pages   (-> (dsh/lookup-file-data state)
                        (get :pages))]
        (if (contains? pages page-id)
          (rx/empty)
          (rx/of (dcm/go-to-workspace :page-id (first pages))))))))
