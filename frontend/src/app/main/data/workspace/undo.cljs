;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.undo
  "Undo management for the workspace.

   There are **undo entries**, **undo transactions** and **undo groups**.
   The undo stack has a maximum size of `MAX-UNDO-SIZE` (50) entries.

   Undo entries:
     - Consist of `:undo-changes`, `:redo-changes`, `:undo-group`, `:tags`, `:translation`
     - Can be:
       - `added`: pushes a new entry on top of the undo stack
       - `stacked`: appends undo/redo changes to the last undo item under
         `:workspace-undo :items`
       - `accumulated`: extends the current transaction with new changes

   Undo transactions:
    - Are a way to incrementally merge incoming changes into a single
      undo entry.
    - Are identified by a unique id, typically a `(js/Symbol)`
    - There is at most one current undo transaction.
    - Have a timeout, defaulting to 20s
    - Are _accumulated_ into the current transaction via
      `accumulate-undo-entry`

   Undo groups:
    - Can contain multiple undo entries
    - Undo entries in a groups must be consecutive

   Undo tags:
    - Known values: `:alt-duplication` (copy-and-move with mouse+alt)
   "
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cpc]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence.lock :as plk]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(def ^:private
  discard-transaction-time-millis (* 20 1000))

(def ^:private
  schema:undo-entry
  [:map {:title "undo-entry"}
   [:undo-changes [:vector cpc/schema:change]]
   [:redo-changes [:vector cpc/schema:change]]
   [:undo-group ::sm/uuid]
   ;; Undo groups of every commit the entry holds
   [:undo-groups {:optional true} [:set ::sm/uuid]]
   [:tags [:set :keyword]]
   [:selected-before {:optional true} [:maybe [:set ::sm/uuid]]]
   [:selected-after {:optional true} [:maybe [:set ::sm/uuid]]]
   [:translation :boolean]
   ;; When the entry was pushed onto the stack; used by the actions
   ;; history panel to show a relative timestamp next to each entry.
   ;; Issue #7660.
   [:timestamp {:optional true} [:maybe some?]]
   ;; Display name of the profile that created the entry. The undo
   ;; stack is client-side per profile, so this is always the current
   ;; user; still stored explicitly so the UI does not need to reach
   ;; into profile state while rendering. Issue #7660.
   [:by {:optional true} [:maybe :string]]])

(def check-undo-entry
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
  "Updates the state to point to a specific index in the undo stack.
   Used to materialize the undo stack when the user selects an entry
   in the undo history."
  [_changes index]
  (ptk/reify ::materialize-undo
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-undo assoc :index index))))

(defn- profile-display-name
  "Best-effort display name for the current profile. Prefers the full
  name, falls back to the email, and finally to nil so the UI can
  simply skip the 'by …' suffix when we have nothing useful to show.
  Issue #7660."
  [state]
  (let [profile (get state :profile)]
    (or (:fullname profile)
        (:email profile))))

(defn- stamp-entry
  "Attach creation metadata to an undo entry. We only stamp the timestamp
  and author when they are missing so already-enriched entries (e.g.
  coming from an accumulated transaction that was opened earlier) keep
  their original creation time and attribution. Issue #7660."
  [state entry]
  (cond-> entry
    (nil? (:timestamp entry)) (assoc :timestamp (ct/now))
    (nil? (:by entry))        (assoc :by (profile-display-name state))))

(defn- add-undo-entry
  [state entry]
  (if (and entry
           (not-empty (:undo-changes entry))
           (not-empty (:redo-changes entry)))
    (let [index (get-in state [:workspace-undo :index] -1)
          items (get-in state [:workspace-undo :items] [])
          items (->> items (take (inc index)) (into []))
          items (conj-undo-entry items (stamp-entry state entry))]
      (-> state
          (update :workspace-undo assoc :items items
                  :index (min (inc index)
                              (dec MAX-UNDO-SIZE)))))
    state))

(defn- stack-undo-entry
  "Extends the selected undo entry with new changes if it
   exists, or creates a new entry if it doesn't. When stacking onto an
   existing entry, the entry's original timestamp is preserved so the
   history panel keeps showing when the action originated. Issue #7660."
  [state {:keys [undo-changes redo-changes undo-group selected-after translation] :as entry} index]
  (if (>= index 0)
    (update-in state [:workspace-undo :items index]
               (fn [item]
                 (cond-> (-> item
                             (update :undo-changes #(into undo-changes %))
                             (update :redo-changes #(into % redo-changes))
                             (update :undo-groups (fnil conj #{}) undo-group)
                             (update :translation #(and % translation)))
                   (= index (get-in state [:workspace-undo :index]))
                   (assoc :selected-after selected-after))))
    (add-undo-entry state entry)))

(defn- accumulate-undo-entry
  "Extends the current undo transaction with new changes."
  [state {:keys [undo-changes redo-changes undo-group tags selected-before selected-after translation]}]
  (-> state
      (update-in [:workspace-undo :transaction :undo-changes] #(into undo-changes %))
      (update-in [:workspace-undo :transaction :redo-changes] #(into % redo-changes))
      (update-in [:workspace-undo :transaction :translation]
                 #(and % translation))
      (cond-> (nil? (get-in state [:workspace-undo :transaction :undo-group]))
        (assoc-in [:workspace-undo :transaction :undo-group] undo-group))
      (update-in [:workspace-undo :transaction :undo-groups] (fnil conj #{}) undo-group)
      (cond-> (nil? (get-in state [:workspace-undo :transaction :selected-before]))
        (assoc-in [:workspace-undo :transaction :selected-before] selected-before))
      (assoc-in [:workspace-undo :transaction :selected-after] selected-after)
      (assoc-in [:workspace-undo :transaction :tags] tags)))

(defn append-undo
  "Add changes to undo history. Stacked changes extend their applied undo
   group when present, otherwise the current entry. An open transaction
   accumulates changes unless they stack into another applied group."
  [entry stack?]

  (assert (check-undo-entry entry))
  (assert (boolean? stack?))

  (ptk/reify ::append-undo
    ptk/UpdateEvent
    (update [_ state]
      (let [selected-after (dm/get-in state [:workspace-local :selected])
            entry          (assoc entry
                                  :selected-after selected-after
                                  :undo-groups #{(:undo-group entry)})
            index          (get-in state [:workspace-undo :index] -1)
            transaction    (get-in state [:workspace-undo :transaction])
            in-group?      (fn [item group]
                             (or (= group (:undo-group item))
                                 (contains? (:undo-groups item) group)))
            group-index    (when (and stack? (:undo-group entry)
                                      (not (in-group? transaction (:undo-group entry))))
                             (last (keep-indexed
                                    (fn [index item]
                                      (when (in-group? item (:undo-group entry)) index))
                                    (take (inc index) (get-in state [:workspace-undo :items])))))]
        (cond
          ;; A delayed derived change still belongs to its primary action,
          ;; even after another edit or transaction has started.
          (some? group-index)
          (stack-undo-entry state entry group-index)

          (and (get-in state [:workspace-undo :transaction])
               (or (not stack?)
                   (d/not-empty? (get-in state [:workspace-undo :transaction :undo-changes]))
                   (d/not-empty? (get-in state [:workspace-undo :transaction :redo-changes]))))
          (accumulate-undo-entry state entry)

          stack?
          (stack-undo-entry state entry index)

          :else
          (add-undo-entry state entry))))))

(def empty-tx
  {:undo-changes [] :redo-changes [] :translation true})

(declare check-open-transactions)

(defn start-undo-transaction
  "Start a transaction, so that changes in it are added together into a single undo entry."
  [id & {:keys [timeout] :or {timeout discard-transaction-time-millis}}]
  (ptk/reify ::start-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (log/info :hint "start-undo-transaction")

      (update state :workspace-undo
              (fn [undo-state]
                (-> undo-state
                    ;; Commits made while it is open without an undo group of
                    ;; their own get this one
                    (update :transaction #(d/nilv % (assoc empty-tx :commit-group (uuid/next))))
                    (update :transactions-pending assoc id (ct/now))))))

    ptk/WatchEvent
    (watch [_ _ stream]
      (when (and timeout (pos? timeout))
        (let [stoper (rx/filter (ptk/type? ::start-undo-transaction) stream)]
          (->> (rx/of (check-open-transactions timeout))
               ;; Wait the configured time
               (rx/delay timeout)
               (rx/take-until stoper)))))))

(defn discard-undo-transaction
  "Updates the state to discard any current and pending undo transaction."
  []
  (ptk/reify ::discard-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (log/info :hint "discard-undo-transaction")
      (update state :workspace-undo dissoc :transaction :transactions-pending))))

(defn- add-transaction-undo-entry
  "Conditionally add an undo entry from the current transaction. That
  only happens when no pending transactions are available and the
  current transaction exists."
  [state]
  (let [undo-state (get state :workspace-undo)
        current-tx (get undo-state :transaction)
        pending-tx (get undo-state :transactions-pending)]
    (if (and (some? current-tx)
             (empty? pending-tx))
      (-> state
          (add-undo-entry (dissoc current-tx :commit-group))
          (update :workspace-undo dissoc :transaction))
      state)))

(defn commit-undo-transaction
  [id]
  (ptk/reify ::commit-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (log/info :hint "commit-undo-transaction")
      (-> state
          (update-in [:workspace-undo :transactions-pending] dissoc id)
          (add-transaction-undo-entry)))))

(defn forget-undo-groups
  "Drops the undo history from the first entry holding a commit of
  `undo-groups` on, and empties the open transaction when the history is
  cut or it holds one of them; its later commits keep the undo group of
  its earlier ones. `undo-index`, when not nil, is the index the oldest
  reverted undo or redo commit moved away from: the history points there
  again."
  [undo-groups undo-index]
  (ptk/reify ::forget-undo-groups
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-undo
              (fn [{:keys [items index transaction] :as undo}]
                (let [reverted?   #(some undo-groups (:undo-groups %))
                      first-index (d/index-of-pred items reverted?)
                      last-index  (dec (count items))
                      index       (if (some? undo-index)
                                    (min undo-index last-index)
                                    (d/nilv index last-index))]
                  (cond-> (assoc undo :index index)
                    (some? first-index)
                    (assoc :items (subvec items 0 first-index)
                           :index (min index (dec first-index)))

                    (and (some? transaction)
                         (or (some? first-index) (reverted? transaction)))
                    (assoc :transaction
                           (assoc empty-tx :commit-group (or (:undo-group transaction)
                                                             (:commit-group transaction)))))))))))

(def reinitialize-undo
  "Clears the undo stack, removing all entries and transactions."
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-undo {}))))

(defn check-open-transactions
  [timeout]
  (ptk/reify ::check-open-transactions
    ptk/WatchEvent
    (watch [_ state _]
      (log/info :hint "check-open-transactions" :timeout timeout)
      (let [pending-ts (-> (dm/get-in state [:workspace-undo :transactions-pending])
                           (update-vals #(ct/diff-ms % (ct/now))))]
        (->> pending-ts
             (filter (fn [[_ ts]] (>= ts timeout)))
             (rx/from)
             (rx/tap #(js/console.warn (dm/str "FORCE COMMIT TRANSACTION AFTER " (second %) "MS")))
             (rx/map first)
             (rx/map commit-undo-transaction))))))

(defn- restore-selection
  "Restores the selection state from an undo entry."
  [selected-ids]
  (ptk/reify ::restore-selection
    ptk/UpdateEvent
    (update [_ state]
      (if (some? selected-ids)
        (assoc-in state [:workspace-local :selected] selected-ids)
        state))))

(defn- history-runs
  "The runs of `pairs` (`[applied inverse]` per history entry, in the order
   they apply) that share a destination file, as `[file-id applied inverse]`.
   The inverse of a run undoes it; within an entry, the last run of a file
   carries the entry's inverse for that file."
  [pairs]
  (->> pairs
       (mapcat (fn [[applied inverse]]
                 (let [runs (partition-by ::file-id applied)
                       last-run (into {} (map-indexed (fn [i run] [(::file-id (first run)) i])) runs)]
                   (map-indexed (fn [i run]
                                  (let [file-id (::file-id (first run))]
                                    [file-id
                                     (vec run)
                                     (if (= i (get last-run file-id))
                                       (filterv #(= file-id (::file-id %)) inverse)
                                       [])]))
                                runs))))
       (partition-by first)
       (map (fn [segments]
              [(ffirst segments)
               (into [] (mapcat second) segments)
               (into [] (mapcat #(nth % 2)) (reverse segments))]))))

(defn- commit-history-changes
  "Replay the history `pairs` (see `history-runs`) in order, keeping each run
   in its destination file. Older entries without a destination use the
   current file. Each commit is undoable by a rollback, which also puts the
   history back to `undo-index`. `translation` marks them as moves only."
  [origin undo-index pairs translation]
  (->> (history-runs pairs)
       (map (fn [[file-id applied inverse]]
              (dch/commit-changes {:file-id file-id
                                   :redo-changes (mapv #(dissoc % ::file-id) applied)
                                   :undo-changes (mapv #(dissoc % ::file-id) inverse)
                                   :undo-index undo-index
                                   :origin origin
                                   :save-undo? false
                                   :translation? translation})))
       (rx/from)))

(defn undo-to-index
  "Repeat undoing or redoing until dest-index is reached."
  [dest-index]
  (ptk/reify ::undo-to-index
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        ;; While saves fail the history stays still: its commit would be
        ;; refused after the index moved
        (when-not (or (plk/locked? state)
                      (and (or (some? edition) (some? (:object drawing)))
                           (not (ctl/grid-layout? objects edition))))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when (and (some? items)
                       (<= -1 dest-index (dec (count items))))
              (let [pairs (cond
                            (< dest-index index)
                            (->> (subvec items (inc dest-index) (inc index))
                                 (reverse)
                                 (map (juxt :undo-changes :redo-changes)))
                            (> dest-index index)
                            (->> (subvec items (inc index) (inc dest-index))
                                 (map (juxt :redo-changes :undo-changes)))
                            :else [])
                    changes (into [] (mapcat first) pairs)
                    ;; a replay of moves only is itself a move
                    translation (->> (subvec items
                                             (inc (min dest-index index))
                                             (inc (max dest-index index)))
                                     (every? :translation))]
                (when (seq changes)
                  (rx/concat
                   (rx/of (materialize-undo changes dest-index))
                   (commit-history-changes it index pairs translation)))))))))))

(declare ^:private assure-valid-current-page)

(def undo
  "Undo the last action, or the last action in a group.
   If there is an open transaction, it will undo to the last transaction
   index."
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]

        ;; Editors handle their own undo's; nothing is undone while saves fail
        (when (and (not (plk/locked? state))
                   (or (and (nil? edition) (nil? (:object drawing)))
                       (ctl/grid-layout? objects edition)))
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
                  (let [first-item (get items undo-group-index)]
                    (rx/of (undo-to-index (dec undo-group-index))
                           (restore-selection (:selected-before first-item))))
                  (rx/concat
                   (rx/of (materialize-undo changes (dec index)))
                   (commit-history-changes it index [[changes (:redo-changes item)]] (:translation item))
                   (rx/of (restore-selection (:selected-before item))
                          (assure-valid-current-page))))))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]

        ;; Editors handle their own undo's; nothing is undone while saves fail
        (when (and (not (plk/locked? state))
                   (or (and (nil? edition) (nil? (:object drawing)))
                       (ctl/grid-layout? objects edition)))
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
                  (let [last-item (get items redo-group-index)]
                    (rx/of (undo-to-index redo-group-index)
                           (restore-selection (:selected-after last-item))))
                  (rx/concat
                   (rx/of (materialize-undo changes (inc index)))
                   (commit-history-changes it index [[changes (:undo-changes item)]] (:translation item))
                   (rx/of (restore-selection (:selected-after item)))))))))))))

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
