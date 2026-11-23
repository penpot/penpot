;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.persistence
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.changes :as cpc]
   [app.common.files.changes-merge :as mch]
   [app.common.files.tokens :as cfo]
   [app.common.logging :as log]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.common :as-alias dc]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as-alias modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence.commits :as pcm]
   [app.main.data.persistence.lock :as plk]
   [app.main.data.persistence.order :as porder]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.state :as path.state]
   [app.main.data.workspace.undo :as dwu]
   [app.main.errors :as errors]
   [app.main.features :as features]
   [app.main.features.pointer-map :as fpmap]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare ^:private run-persistence-task)
(declare ^:private persist-commit)
(declare ^:private resume-persistence)
(declare ^:private slow-retry-cycle)
(declare ^:private flush-buffer)
(declare ^:private commit-persisted)
(declare ^:private get-file-order)
(declare ^:private settle-opt-in)
(declare ^:private reconcile-file)
(declare ^:private prune-removed-shapes)
(declare rejected-tag)
(declare repaired-rejected-tag)
(declare ^:private notify-file-repairs)

(log/set-level! :warn)

(def revn-data (atom {}))
(defonce ^:private active-requests (atom #{}))
(def queue-conj (fnil conj #queue []))

(def force-persist? #(= % ::force-persist))

(def slow-retry-delay-ms
  "Pause between the attempts of a queue whose backoff is spent: one attempt
  per cycle, until it saves or the retry window closes."
  30000)

(def retry-give-up-ms
  "How long a queue that keeps failing on transport errors is sent again."
  (* 24 60 60 1000))

(def slow-failure-ms
  "An attempt that fails after this long may have kept the backend busy,
  as a gateway timeout does, so it counts towards `max-slow-failures`."
  (* 90 1000))

(def max-slow-failures
  "Slow failures a queued commit may collect before the slow cycle stops
  sending it. Fast failures, such as being offline, never count."
  3)

;; Wait for the changes stored before a saved commit, then reload the file.
(def ^:private unseen-changes-timeout-ms 10000)

(def ^:private kept-rejected-tag :persistence-rejected-kept)

(def ^:private saving-stall-timeout-ms (* 5 60 1000))
(def ^:private saving-check-interval-ms 30000)
(def ^:private save-wait-timeout-ms (* 2 60 1000))
;; Longer than the stall threshold so a hung save is reported before it is retried.
(def ^:private save-request-timeout-ms (* 7 60 1000))

(defn terminal-status?
  "True when a persistence snapshot releases waiters: a failed save, or
  a settled (`nil` / `:saved`) empty queue and buffer. Anything else —
  `:pending`, `:saving`, the `:retrying` episode, or commits still in the
  buffer (the status turns `:saved` as soon as the queue empties) — keeps
  waiting."
  [{:keys [status queue buffer reconciliations]}]
  (boolean
   (or (= status :error)
       (and (empty? reconciliations)
            (empty? queue)
            (empty? buffer)
            (or (nil? status) (= status :saved))))))

(defn wait-persisted-or-error
  "Returns an observable that emits the first terminal persistence status
   (nil | :saved) and completes. Raises when the queue has failed and, with
   a timeout-ms, when persistence does not settle in time."
  ([] (wait-persisted-or-error save-wait-timeout-ms))
  ([timeout-ms]
   (let [base (->> (rx/from-atom refs/persistence {:emit-current-value? true})
                   (rx/filter terminal-status?)
                   (rx/take 1)
                   (rx/mapcat (fn [{:keys [status error]}]
                                (if (= status :error)
                                  (rx/throw (ex-info "Changes could not be saved"
                                                     (merge {:type :persistence :code :save-failed} error)))
                                  (rx/of status)))))]
     (cond->> base
       timeout-ms
       (rx/timeout timeout-ms
                   (rx/throw (ex-info "Timed out waiting for changes to be saved"
                                      {:type :persistence :code :save-timeout})))))))

(defn wait-persisted
  "Best-effort variant of `wait-persisted-or-error`: a failed or timed out
   save completes the observable silently instead of raising."
  ([] (wait-persisted nil))
  ([timeout-ms]
   (->> (wait-persisted-or-error timeout-ms)
        (rx/catch (fn [_] (rx/empty))))))

(defn force-persist-and-wait
  "Convenience that emits the force-persist event and then waits for
   persistence to settle. Returns the combined observable."
  ([] (force-persist-and-wait nil))
  ([timeout-ms]
   (rx/concat (rx/of ::force-persist) (wait-persisted timeout-ms))))

(defn- files-queued?
  "True when the queue in `pstate` still holds changes of any of `file-ids`."
  [{:keys [queue index reconciliations]} file-ids]
  (boolean (or (some file-ids (keys reconciliations))
               (some #(contains? file-ids (get-in index [% :file-id])) queue))))

(defn unsaved-changes?
  "True when any of `file-ids` has changes the server does not have yet."
  [state file-ids]
  (files-queued? (:persistence state) file-ids))

(defn force-persist-files-and-wait
  "Sends the pending changes and waits until no change of `file-ids` is
  queued, saving fails, or `timeout-ms` passes. Never raises; check
  `unsaved-changes?` afterwards."
  [file-ids timeout-ms]
  (rx/concat
   (rx/of ::force-persist)
   (->> (rx/from-atom refs/persistence {:emit-current-value? true})
        (rx/filter #(or (= :error (:status %))
                        (not (files-queued? % file-ids))))
        (rx/take 1)
        (rx/ignore)
        (rx/timeout timeout-ms (rx/empty)))))

(defn- next-status
  "Refuses downgrades: a save in progress stays :saving, a failed save
  stays :error until persistence is resumed, and a retrying episode stays
  :retrying until it saves or errors (re-entries send under the episode
  instead of resetting it)."
  [from to]
  (cond
    (and (= to :pending) (= from :saving))         from
    (and (= from :error) (#{:pending :saving} to)) from
    (and (= from :retrying) (#{:pending :saving} to)) from
    :else                                          to))

(defn- count-unsaved-commits
  "Local commits not saved yet, queued or buffered, of every file."
  [state]
  (let [{:keys [queue index buffer]} (:persistence state)]
    (+ (count (mapcat #(get-in index [% :commits]) queue))
       (count buffer))))

(defn- nothing-to-save?
  [{:keys [buffer queue]}]
  (and (empty? buffer) (empty? queue)))

(defn- update-status
  "Moves persistence to `status`. A delayed :pending notice is ignored
  when nothing is left to save (e.g. a rollback already dropped the edit)."
  [status]
  (ptk/reify ::update-status
    ptk/UpdateEvent
    (update [_ state]
      (update state :persistence
              (fn [pstate]
                (log/trc :hint "update-status"
                         :from (:status pstate)
                         :to status)
                (let [status (if (and (= :pending status) (nothing-to-save? pstate))
                               (:status pstate)
                               (next-status (:status pstate) status))]
                  (cond-> (assoc pstate :status status)
                    (#{:pending :saving} status)
                    (update :last-progress-at d/nilv (inst-ms (ct/now)))

                    (#{:error :saved} status)
                    (dissoc :run-id :last-progress-at :stall-reported))))))))

(def ^:private busy-server-codes
  "Database timeout codes shown as a busy server."
  #{:statement-timeout :idle-in-transaction-timeout})

(defn- busy-server-error?
  "True when a failure should show the busy-server retry notice. A
  timeout alone says nothing about whether the save committed."
  [{:keys [type cause-type code]}]
  (let [type (or cause-type type)]
    (or (= :rate-limit type)
        (and (= :server-error type)
             (contains? busy-server-codes code)))))

(defn transient-error?
  "True when a save failure is worth retrying with backoff: a transient
  transport failure (from the shared `repo/retryable-types` set, which says
  nothing about the edits themselves), a busy backend, or an unusable save
  response. Everything else (auth, validation, state) is terminal and keeps
  the `:error` path. Checked against both `:type` and `:cause-type` because
  `persistence-failed` wraps the original cause under `:type :persistence`."
  [{:keys [type cause-type code] :as error}]
  (boolean
   (or (contains? rp/retryable-types type)
       (contains? rp/retryable-types cause-type)
       (busy-server-error? error)
       (= :invalid-save-response code))))

(def ^:private retry-delays-ms
  "Backoff delays (ms) between save retries; the count is the retry budget.
  A plain value (not a dynamic var): dynamic bindings do not survive `await`
  continuations, so tests instant-trigger retries by stubbing `rx/timer`."
  [2000 8000 20000])

(def ^:private reconnecting-tag
  "Tag of the single reconnect notice: re-showing replaces it by
  construction (the store holds one toast), and it is hidden by tag on
  save or on terminal failure."
  :persistence-reconnecting)

(defn- retry-window-open?
  "True while a failing queue may still be sent again."
  [pstate]
  (let [since (:failing-since pstate)]
    (or (nil? since)
        (< (- (inst-ms (ct/now)) since) retry-give-up-ms))))

(defn- slow-retry-pending?
  "True when the queue is halted by a transport failure that the slow retry
  cycle keeps sending. A commit whose attempts keep failing slowly is not
  sent again: each attempt may cost the backend the whole save."
  [{:keys [status error slow-failures] :as pstate}]
  (and (= :error status)
       (transient-error? error)
       (retry-window-open? pstate)
       (< (or slow-failures 0) max-slow-failures)))

(defn save-retrying?
  "True while the slow cycle keeps resending a failed queue, including
  while one of its attempts is in flight."
  [pstate]
  (or (true? (:recovering pstate))
      (slow-retry-pending? pstate)))

(defn- report-stalled-persistence
  [now]
  (ptk/reify ::report-stalled-persistence
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:persistence :stall-reported] true))

    ptk/EffectEvent
    (effect [_ state _]
      (let [{:keys [queue index status run-id last-progress-at]} (:persistence state)
            commit-id (peek queue)
            commit    (get index commit-id)
            hint      "File saving has made no progress for more than five minutes"
            cause     (ex/error :type :persistence
                                :hint hint
                                :code :saving-stalled
                                :file-id (or (:file-id commit) (:current-file-id state))
                                :commit-id commit-id
                                :run-id run-id
                                :status status
                                :queued-commits (count queue)
                                :elapsed-ms (- now last-progress-at)
                                :can-edit (dm/get-in state [:permissions :can-edit])
                                :read-only (dm/get-in state [:workspace-global :read-only?])
                                :preview-id (dm/get-in state [:workspace-global :preview-id])
                                :render-context-lost? (dm/get-in state [:render-state :lost]))]
        (errors/submit-report :event-name "handled-exception"
                              :hint hint
                              :report (fn [] (errors/generate-report cause))
                              :cause cause)))))

(defn- check-persistence
  []
  (ptk/reify ::check-persistence
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [status queue last-progress-at stall-reported]} (:persistence state)
            now (inst-ms (ct/now))]
        (when (and (#{:pending :saving :retrying} status)
                   (seq queue)
                   last-progress-at
                   (not stall-reported)
                   (> (- now last-progress-at) saving-stall-timeout-ms))
          (rx/of (report-stalled-persistence now)))))))

(defn- get-preview-file-id
  [state]
  (when (get-in state [:workspace-global :preview-id])
    (:current-file-id state)))

(defn- previewing-file?
  [state file-id]
  (and (some? file-id) (= file-id (get-preview-file-id state))))

(defn- get-live-file-path
  [state file-id]
  (if (previewing-file? state file-id)
    [:workspace-versions :backup]
    [:files file-id]))

(defn- get-live-file
  [state file-id]
  (get-in state (get-live-file-path state file-id)))

(defn- update-file-revn
  ([file-id revn] (update-file-revn file-id revn nil))
  ([file-id revn request]
   (let [current? (fn [state]
                    (or (nil? request)
                        (and (= (:load-id request) (:workspace-load-id state))
                             (= (:vern request) (:vern (get-file-order state file-id))))))]
     (ptk/reify ::update-file-revn
       ptk/UpdateEvent
       (update [_ state]
         (if (current? state)
           (d/update-in-when state (get-live-file-path state file-id) update :revn max revn)
           state))

       ptk/EffectEvent
       (effect [_ state _]
         (when (current? state)
           (swap! revn-data update file-id (fnil max 0) revn)))))))

(defn- remember-remote-changes
  [pstate file-id revn changes]
  (let [number (inc (:remote-seq pstate 0))]
    (cond-> (-> pstate
                (assoc :remote-seq number)
                (update-in [:remote-revn file-id] (fnil max revn) revn))
      (seq (pcm/pending-commits pstate file-id))
      (update-in [:remote-changes file-id] (fnil conj [])
                 {:seq number :changes changes}))))

(defn- record-remote-changes
  "Numbers a remote commit, keeps the highest revision received per file
  (`:remote-revn`) and, while `file-id` has pending commits, keeps its
  changes for a rollback to apply again."
  [file-id revn changes]
  (ptk/reify ::record-remote-changes
    IDeref
    (-deref [_] {:file-id file-id :revn revn})

    ptk/UpdateEvent
    (update [_ state]
      (update state :persistence remember-remote-changes file-id revn changes))))

(defn- get-file-order
  [state file-id]
  (let [{:keys [vern revn]} (get-live-file state file-id)
        order (get-in state [:persistence :file-order file-id])]
    (if (and order (= (or vern 0) (:vern order)))
      (cond-> order
        (> (or revn 0) (:latest order)) (assoc :latest revn :gap true))
      (porder/loaded (or vern 0) (or revn 0)))))

(defn prepare-file-load
  "Before replaying local edits, drop unsent compensation unless the loaded
  revision is already a proven base. Returns [state compensation-skipped]."
  [state {:keys [id vern revn]}]
  (if (and (some? vern) (some? revn)
           (let [order (get-file-order state id)]
             (not (and (= vern (:vern order))
                       (porder/proven-base? order revn)))))
    (let [pstate  (:persistence state)
          trimmed (pcm/without-unsent-compensations pstate id vern (complement ::request-id))]
      [(assoc state :persistence (pcm/mark-undo-uncertain trimmed id vern))
       (not= pstate trimmed)])
    [state false]))

(defn notify-kept-compensations
  []
  (ptk/reify ::notify-kept-compensations
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [links tag]} (:notification state)]
        (rx/of
         (ntf/show (cond-> {:content (tr "errors.save-rejected-kept-saved-changes")
                            :type :toast
                            :level :warning
                            :timeout nil
                            :tag kept-rejected-tag}
                     (and (seq links) (contains? #{rejected-tag repaired-rejected-tag kept-rejected-tag} tag))
                     (assoc :links links))))))))

(defn file-base-loaded
  "Starts the proof of revision order from a freshly read file, not the
  greatest revision received over WebSocket."
  [state {:keys [id vern revn]}]
  (if (and (some? vern) (some? revn))
    (let [request  (get-in state [:persistence :reconciliations id])
          deferred (get-in state [:persistence :preview-effects id])
          replaced? (and request (not= vern (:vern request)))]
      (cond-> (-> state
                  (assoc-in [:persistence :file-order id] (porder/loaded vern revn))
                  (update :persistence d/update-in-when [:remote-revn] dissoc id))
        replaced? (update-in [:persistence :reconciliations] dissoc id)
        (and deferred (not= vern (:vern deferred)))
        (update-in [:persistence :preview-effects] dissoc id)
        (and replaced? (:entry-id request))
        (d/update-in-when [:persistence :index (:entry-id request)] assoc ::acknowledged true)))
    state))

(defn- waiting-save
  [state file-id]
  (let [{:keys [queue index]} (:persistence state)
        entry (get index (peek queue))]
    (when (and (= file-id (:file-id entry)) (::save-result entry))
      entry)))

(defn- record-applied-message
  [state {:keys [file-id revn vern repaired changes modified-at] :as message}]
  (let [order (porder/applied (get-file-order state file-id) message)]
    (-> state
        (assoc-in [:persistence :file-order file-id] order)
        (update :persistence remember-remote-changes file-id revn changes)
        (cond-> repaired (update :persistence pcm/mark-undo-uncertain file-id vern))
        (dsh/update-file file-id #(cond-> (assoc % :revn (:latest order))
                                    modified-at (assoc :modified-at modified-at))))))

(defn apply-remote-message
  "Installs a file or library message, or reads a fresh base if that
  message would make the loaded data go backwards."
  [{:keys [file-id revn vern repaired repair-base-revn revision-ids library-change] :as message} library-effects]
  (let [decision (volatile! :ignore)
        library? (= :library-change (:type message))
        repairs  (when (and repaired (not library?)) #{[revn (peek revision-ids)]})]
    (dch/rebase-commits
     file-id
     {:prepare
      (fn [state]
        (let [order   (get-file-order state file-id)
              vern    (:vern order)
              message (assoc message :vern (get message :vern vern))
              pending (pcm/pending-commits (:persistence state) file-id vern)
              order-decision (porder/decision order message library?)
              action  (cond
                        (not= vern (:vern message)) :ignore
                        (= :ignore order-decision) :ignore
                        (get-in state [:persistence :reconciliations file-id]) :reconcile
                        (and (= :apply order-decision)
                             (or (previewing-file? state file-id)
                                 (some :undo-uncertain pending)
                                 (and (or repaired repair-base-revn)
                                      (some :compensation pending))))
                        :reconcile
                        :else order-decision)]
          (vreset! decision action)
          (when (= :apply action)
            {:undo pending
             :remote (:changes message)
             :redo pending
             :separate-steps true
             :on-applied #(record-applied-message % message)})))
      :on-skipped
      (fn [state]
        (cond-> state
          (= :reconcile @decision)
          (d/update-in-when [:persistence :reconciliations file-id]
                            #(cond-> (update % :minimum max revn)
                               library-change (assoc :library-change true)))))
      :after
      (fn [state outcome]
        (let [waiting (waiting-save state file-id)
              scope   (when repairs {:file-id file-id :load-id (:workspace-load-id state) :vern vern})]
          (cond
            (or (= :failed outcome) (= :reconcile @decision))
            (rx/of (reconcile-file file-id {:minimum revn :repairs repairs
                                            :library-change library-change}))

            (= :applied outcome)
            (rx/concat
             ;; Legacy saves still wait on this notification; the model and
             ;; revision evidence have already been updated.
             (rx/of (ptk/data-event ::record-remote-changes {:file-id file-id :revn revn}))
             (if repairs
               (rx/of (notify-file-repairs scope repairs false))
               (rx/empty))
             (if library-effects (library-effects) (rx/empty))
             (if (and waiting (not (:confirmed waiting)))
               (rx/of (settle-opt-in (:id waiting)))
               (rx/empty)))

            (and repairs (= :ignore @decision))
            (rx/of (notify-file-repairs scope repairs true))

            :else
            (rx/empty))))})))

(defn- stale-commit?
  "True when `commit` was made on another version of its file than the
  loaded one, which a peer's restore replaced: its changes are not in the
  local data and the server refuses them. A file loaded with no version
  known is never stale."
  [state {:keys [file-id file-vern]}]
  (let [vern (:vern (get-live-file state file-id))]
    (and (some? vern)
         (not= vern file-vern))))

(defn- last-unseen-revn
  "The highest revision above `seen` (the highest one received) of the rows
  other sessions stored in `lagged`, the rows a save answer lists after
  the revision it was sent with. Nil when there is none, or when the save
  stored nothing (the last row is not this session's)."
  [lagged session-id seen]
  (let [own? #(= session-id (:session-id %))]
    (when (own? (last lagged))
      (transduce (comp (remove own?)
                       (map :revn)
                       (filter #(> % seen)))
                 (completing (fnil max 0))
                 nil
                 lagged))))

(defn- settle-legacy-save
  "Acknowledges the saved `commit` once this tab received every change the
  server stored before it (from `lagged`, the save answer's rows), such as
  the repair of a damaged file. Until then its commits stay pending, so a
  late change is applied under them, in the server's order. A change that
  does not arrive in time reloads the file."
  [commit lagged]
  (let [needed (volatile! nil)
        deferred (volatile! false)]
    (ptk/reify ::settle-saved-commit
      ptk/UpdateEvent
      (update [_ state]
        (let [file-id (:file-id commit)
              confirmed (get-in state [:persistence :index (:id commit) :confirmed])
              seen    (dm/get-in state [:persistence :remote-revn file-id] -1)
              revn    (when (and (not confirmed)
                                 (= file-id (:current-file-id state))
                                 (not (stale-commit? state commit)))
                        (last-unseen-revn lagged (:session-id state) seen))]
          (vreset! deferred confirmed)
          (vreset! needed revn)
          (cond-> state
            (some? revn)
            (d/update-in-when [:persistence :index (:id commit)] assoc ::awaiting revn))))

      ptk/WatchEvent
      (watch [_ _ stream]
        (if-let [revn @needed]
          (let [file-id   (:file-id commit)
                arrived-s (->> stream
                               (rx/filter (ptk/type? ::record-remote-changes))
                               (rx/map deref)
                               (rx/filter #(and (= file-id (:file-id %))
                                                (>= (:revn %) revn)))
                               (rx/map (constantly ::arrived)))
                left-s    (->> stream
                               (rx/filter (ptk/type? ::dw/finalize-workspace))
                               (rx/map (constantly ::left)))
                timeout-s (->> (rx/timer unseen-changes-timeout-ms)
                               (rx/map (constantly ::timeout)))]
            (->> (rx/merge arrived-s left-s timeout-s)
                 (rx/take 1)
                 (rx/mapcat (fn [outcome]
                              (rx/concat
                               (rx/of (commit-persisted commit))
                               (if (= ::timeout outcome)
                                 (rx/of (ptk/event ::dw/reload-current-file))
                                 (rx/empty)))))))
          (if @deferred
            (rx/empty)
            (rx/of (commit-persisted commit))))))))

(defn- mark-confirmed
  [state entry-id]
  (if entry-id
    (d/update-in-when state [:persistence :index entry-id]
                      #(-> % (assoc :confirmed true) (dissoc ::save-result)))
    state))

(defn- protect-unsettled-undo
  [state {:keys [file-id file-vern] :as entry}]
  (if (::save-result entry)
    (update state :persistence
            #(-> %
                 (pcm/mark-undo-uncertain file-id file-vern)
                 (pcm/without-unsent-compensations file-id file-vern (complement ::request-id))))
    state))

(defn- get-reconciliation
  [state file-id]
  (get-in state [:persistence :reconciliations file-id]))

(defn- retry-after-file-read
  "Rechecks an expired backoff after installing the head's file, without
  starting a new retry episode."
  [{:keys [persistence]} file-id]
  (let [{:keys [queue index status suspended retry-token retry-for retry-after-read]} persistence]
    (when (and (= :retrying status)
               (not suspended)
               retry-token
               (= retry-token retry-after-read)
               (= retry-for (peek queue))
               (= file-id (get-in index [retry-for :file-id]))
               (not (contains? (:reconciliations persistence) file-id)))
      (persist-commit retry-for {:token retry-token}))))

(defn- reconciliation-current?
  [state file-id {:keys [token load-id vern]}]
  (and (= token (:token (get-reconciliation state file-id)))
       (= load-id (:workspace-load-id state))
       (= vern (:vern (get-file-order state file-id)))
       (some? (:data (get-live-file state file-id)))))

(defn- install-reconciled-file
  [state {:keys [id vern revn] :as file}]
  (let [previous (get-live-file state id)
        pending  (pcm/pending-commits (:persistence state) id vern)
        data     (reduce (fn [data {:keys [redo-changes]}]
                           (cpc/process-changes data redo-changes false))
                         (:data file)
                         pending)]
    (-> state
        (assoc-in (get-live-file-path state id)
                  (merge file (select-keys previous [:library-of :synced-at :is-indirect])
                         {:data data}))
        (file-base-loaded file)
        (assoc-in [:persistence :file-order id] (porder/recovered (get-file-order state id) revn))
        (update :persistence pcm/mark-undo-uncertain id vern)
        (update :persistence d/update-in-when [:remote-changes] dissoc id)
        (update-in [:persistence :reconciliations] dissoc id))))

(defn- refresh-reconciliation-notice
  []
  (ptk/reify ::refresh-reconciliation-notice
    ptk/WatchEvent
    (watch [_ state _]
      (let [failed (remove (comp :loading val) (get-in state [:persistence :reconciliations]))]
        (if-let [[file-id] (first failed)]
          (rx/of
           (ntf/show {:content (tr "errors.save-reconciliation-failed")
                      :type :toast
                      :level :warning
                      :timeout nil
                      :tag :persistence-reconciliation-failed
                      :links [{:label (tr "labels.retry")
                               :callback #(st/emit! (reconcile-file file-id {:retry true}))}]}))
          (rx/of (ntf/hide :tag :persistence-reconciliation-failed)))))))

(defn- get-token-input
  "The live consumer's active sets, read from its effective token source."
  [state]
  (let [workspace-data (:data (get-live-file state (:current-file-id state)))
        source-id      (cfo/get-effective-tokens-source workspace-data)
        source-data    (:data (get-live-file state source-id))]
    (dsh/get-token-resolution-input (cfo/get-tokens-status workspace-data)
                                    (cfo/get-tokens-lib source-data))))

(defn- get-missing-thumbnail-ids
  "The server data, not pending local edits, determines what can be deleted."
  [state {:keys [id data]}]
  (into #{}
        (comp (filter #(thc/file-id? % id))
              (filter (fn [object-id]
                        (let [{:keys [page-id frame-id]} (thc/parse-object-id object-id)]
                          (nil? (dm/get-in data [:pages-index page-id :objects frame-id]))))))
        (keys (:thumbnails state))))

(defn- defer-preview-effects
  [state file-id {:keys [load-id vern rollback-targets library-change]} token-input]
  (let [path [:persistence :preview-effects file-id]
        old  (get-in state path)
        old  (when (and (= load-id (:load-id old)) (= vern (:vern old))) old)]
    (assoc-in state path {:load-id load-id :vern vern
                          :token-input (if old (:token-input old) token-input)
                          :library-change (or library-change (:library-change old))
                          :rollback-targets (merge-with into (:rollback-targets old) rollback-targets)})))

(defn finish-preview-effects
  "Delivers deferred effects once the backed-up live file is visible again."
  [file-id]
  (let [effects (volatile! nil)]
    (ptk/reify ::finish-preview-effects
      ptk/UpdateEvent
      (update [_ state]
        (let [{:keys [load-id vern token-input] :as deferred}
              (get-in state [:persistence :preview-effects file-id])
              file (get-live-file state file-id)]
          (if (and deferred (not (previewing-file? state file-id)))
            (do
              (when (and (= file-id (:current-file-id state))
                         (= load-id (:workspace-load-id state))
                         (= vern (:vern file))
                         (some? (:data file)))
                (vreset! effects
                         (-> (dissoc deferred :token-input)
                             (assoc :file-id file-id
                                    :tokens-changed (not= token-input (get-token-input state))))))
              (update-in state [:persistence :preview-effects] dissoc file-id))
            state)))

      ptk/WatchEvent
      (watch [_ _ _]
        (if-let [{:keys [rollback-targets] :as effects} @effects]
          (rx/concat
           (if rollback-targets
             (rx/of (ptk/data-event ::rolled-back rollback-targets))
             (rx/empty))
           (rx/of (ptk/event ::dw/reconciled-library-effects (dissoc effects :rollback-targets))))
          (rx/empty))))))

(defn- reconciliation-result
  [file-id request file]
  (let [outcome (volatile! nil)]
    (ptk/reify ::reconciliation-result
      ptk/UpdateEvent
      (update [_ state]
        (if (reconciliation-current? state file-id request)
          (let [{:keys [minimum vern] :as pending} (get-reconciliation state file-id)
                minimum (max minimum (:latest (get-file-order state file-id)))]
            (cond
              (and (= file-id (:id file))
                   (= vern (:vern file))
                   (some? (:data file))
                   (int? (:revn file))
                   (>= (:revn file) minimum))
              (try
                (let [pstate    (:persistence state)
                      trimmed   (pcm/without-unsent-compensations pstate file-id vern (complement ::request-id))
                      installed (install-reconciled-file (assoc state :persistence trimmed) file)
                      preview   (previewing-file? state file-id)
                      consumer-id (:current-file-id state)
                      consumer  (get-live-file state consumer-id)
                      linked?   (and consumer-id (= consumer-id (:library-of (get-live-file state file-id))))
                      consumer-preview (previewing-file? state consumer-id)
                      context   {:file-id consumer-id :load-id (:load-id pending) :vern (:vern consumer)}
                      relevant? (or (= file-id consumer-id)
                                    (= file-id (cfo/get-effective-tokens-source (:data consumer))))
                      token-input (when (or relevant? (and linked? consumer-preview))
                                    (get-token-input state))]
                  (vreset! outcome
                           (assoc pending :installed true
                                  :preview preview
                                  :consumer-context context
                                  :missing-thumbnail-ids (when (and (= file-id consumer-id) (not preview))
                                                           (get-missing-thumbnail-ids state file))
                                  :compensation-skipped (or (:compensation-skipped pending)
                                                            (not= pstate trimmed))
                                  :edited (when (= file-id (:current-file-id state))
                                            (dsh/lookup-shape state (get-in state [:workspace-local :edition])))
                                  :tokens-changed
                                  (and relevant? (not consumer-preview)
                                       (not= token-input (get-token-input installed)))))
                  (cond
                    preview (defer-preview-effects installed file-id pending token-input)
                    (and linked? consumer-preview)
                    (defer-preview-effects installed consumer-id (assoc context :library-change true) token-input)
                    :else installed))
                (catch :default cause
                  (log/wrn :hint "unable to replay pending changes on reconciled file"
                           :file-id (str file-id) :cause cause)
                  (vreset! outcome pending)
                  (assoc-in state [:persistence :reconciliations file-id :loading] false)))

              :else
              (do (vreset! outcome pending)
                  (assoc-in state [:persistence :reconciliations file-id :loading] false))))
          state))

      ptk/WatchEvent
      (watch [_ state _]
        (if-let [{:keys [installed repairs entry-id load-id vern tokens-changed preview edited library-change
                         consumer-context missing-thumbnail-ids compensation-skipped rollback-targets]} @outcome]
          (if installed
            (rx/concat
             (if preview
               (rx/empty)
               (rx/of (ptk/event ::dw/reconciled-file {:file-id file-id :load-id load-id :vern vern
                                                       :tokens-changed tokens-changed
                                                       :consumer-context consumer-context
                                                       :missing-thumbnail-ids missing-thumbnail-ids
                                                       :library-change library-change})))
             (if (and (not preview) (= file-id (:current-file-id state)))
               (rx/of (prune-removed-shapes edited))
               (rx/empty))
             (if compensation-skipped
               (rx/of (notify-kept-compensations))
               (rx/empty))
             (if (seq repairs)
               (rx/of (notify-file-repairs {:file-id file-id :load-id load-id :vern vern} repairs false))
               (rx/empty))
             (if (and rollback-targets (not preview))
               (rx/of (ptk/data-event ::rolled-back rollback-targets))
               (rx/empty))
             (cond
               entry-id (rx/of (commit-persisted {:id entry-id :file-id file-id}))
               (= :retrying (get-in state [:persistence :status]))
               (if-let [retry (retry-after-file-read state file-id)]
                 (rx/of retry)
                 (rx/empty))
               (or (seq (get-in state [:persistence :queue]))
                   (= :saving (get-in state [:persistence :status])))
               (rx/of (run-persistence-task))
               :else (rx/empty))
             (rx/of (refresh-reconciliation-notice)))
            (rx/of (refresh-reconciliation-notice)))
          (rx/empty))))))

(defn- get-save-repair
  [{:keys [repair media-relinked revn lagged]}]
  (cond
    repair [(:revn repair) (:id repair)]
    media-relinked
    ;; In the relink fallback, the repair row precedes the edit row.
    (let [repair-revn (inc revn)]
      [repair-revn (some #(when (= repair-revn (:revn %)) (:id %)) lagged)])))

(defn- reconcile-file
  "Reads the server state without resending a confirmed save. The read
  stays scoped to its file, version, and workspace opening."
  [file-id {:keys [minimum repairs entry-id retry rollback-targets library-change]}]
  (let [started (volatile! nil)]
    (ptk/reify ::reconcile-file
      ptk/UpdateEvent
      (update [_ state]
        (let [{:keys [vern latest]} (get-file-order state file-id)
              known    (get-reconciliation state file-id)
              waiting  (waiting-save state file-id)
              answer   (:answer (::save-result waiting))
              repair   (get-save-repair answer)
              entry-id (or entry-id (:entry-id known) (:id waiting))
              loaded?  (some? (:data (get-live-file state file-id)))]
          (if loaded?
            (let [pstate    (:persistence state)
                  trimmed   (pcm/without-unsent-compensations pstate file-id vern (complement ::request-id))
                  start?    (or (nil? known) (and retry (not (:loading known))))
                  request   (cond-> (assoc known
                                           :entry-id entry-id
                                           :minimum (max (or minimum 0) (or (:minimum known) 0)
                                                         (or (:saved-revn answer) (:revn answer) 0) latest)
                                           :repairs (cond-> (into (or (:repairs known) #{}) repairs)
                                                      repair (conj repair))
                                           :compensation-skipped (or (:compensation-skipped known)
                                                                     (not= pstate trimmed)))
                              rollback-targets (update :rollback-targets #(merge-with into % rollback-targets))
                              library-change (assoc :library-change true)
                              start? (assoc :token (uuid/next)
                                            :load-id (:workspace-load-id state)
                                            :vern vern
                                            :loading true))]
              (when start? (vreset! started request))
              (-> state
                  (assoc :persistence trimmed)
                  (mark-confirmed entry-id)
                  (update :persistence pcm/mark-undo-uncertain file-id vern)
                  (assoc-in [:persistence :reconciliations file-id] request)))
            (-> state
                (protect-unsettled-undo (get-in state [:persistence :index entry-id]))
                (mark-confirmed entry-id)))))

      ptk/WatchEvent
      (watch [_ state stream]
        (if-let [request @started]
          (let [features (features/get-enabled-features state (:current-team-id state))]
            (->> (rx/defer #(rp/cmd! :get-file {:id file-id :features features}))
                 (rx/mapcat fpmap/resolve-file)
                 (rx/timeout unseen-changes-timeout-ms (rx/of nil))
                 (rx/if-empty nil)
                 (rx/take 1)
                 (rx/catch (fn [_] (rx/of nil)))
                 (rx/map #(reconciliation-result file-id request %))
                 (rx/take-until (rx/filter #(or (ptk/type? ::dw/finalize-workspace %)
                                                (ptk/type? ::dw/initialize-workspace %))
                                           stream))))
          (if (and entry-id (nil? (:data (get-live-file state file-id))))
            (rx/of (commit-persisted {:id entry-id :file-id file-id}))
            (rx/empty)))))))

(defn- settle-opt-in
  [entry-id]
  (ptk/reify ::settle-opt-in
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [file-id file-vern] :as entry} (get-in state [:persistence :index entry-id])
            {:keys [answer sent-revn session-id base-proven load-id]} (::save-result entry)
            {:keys [revn saved-revn repair media-relinked]} answer
            revisions (when saved-revn (porder/save-revisions sent-revn session-id answer))
            repairs   (when-let [row (get-save-repair answer)] #{row})
            reconcile #(reconcile-file file-id {:minimum (or saved-revn revn)
                                                :entry-id entry-id
                                                :repairs repairs})]
        (cond
          (or (nil? answer) (:confirmed entry))
          (rx/empty)

          (or (stale-commit? state entry) (nil? (:data (get-live-file state file-id))))
          (rx/of (commit-persisted entry true))

          (or (not= load-id (:workspace-load-id state))
              (get-reconciliation state file-id)
              (previewing-file? state file-id)
              media-relinked
              (::uncertain entry)
              (and saved-revn (not (and base-proven revisions))))
          (rx/of (reconcile))

          (nil? saved-revn)
          (rx/of (commit-persisted entry))

          :else
          (let [decision (volatile! :ignore)]
            (rx/of
             (dch/rebase-commits
              file-id
              {:prepare
               (fn [state]
                 (let [live (get-in state [:persistence :index entry-id])]
                   (when (and (not (:confirmed live))
                              (= (::save-result live) (::save-result entry))
                              (= load-id (:workspace-load-id state))
                              (not (stale-commit? state entry)))
                     (let [order  (get-file-order state file-id)
                           action (if (or (previewing-file? state file-id)
                                          (get-reconciliation state file-id))
                                    :reconcile
                                    (porder/save-decision order revisions))]
                       (vreset! decision action)
                       (when (= :apply action)
                         (let [pending (when repair
                                         (pcm/pending-commits
                                          (:persistence (mark-confirmed state entry-id)) file-id file-vern))
                               message (cond-> {:revn saved-revn
                                                :revision-ids (:revision-ids revisions)}
                                         repair (assoc :repair-base-revn revn))]
                           (if (some #(or (:undo-uncertain %) (:compensation %)) pending)
                             (do (vreset! decision :reconcile) nil)
                             {:undo pending :remote (:changes repair) :redo pending
                              :separate-steps true
                              :on-applied
                              (fn [state]
                                (cond-> (-> state
                                            (mark-confirmed entry-id)
                                            (assoc-in [:persistence :file-order file-id]
                                                      (porder/applied order message))
                                            (dsh/update-file file-id #(assoc % :revn saved-revn)))
                                  repair (update :persistence remember-remote-changes
                                                 file-id saved-revn (:changes repair))
                                  repair (update :persistence pcm/mark-undo-uncertain file-id file-vern)))})))))))
               :after
               (fn [state outcome]
                 (cond
                   (= :applied outcome)
                   (rx/concat
                    (if repairs
                      (rx/of (notify-file-repairs {:file-id file-id :load-id load-id :vern file-vern}
                                                  repairs false))
                      (rx/empty))
                    (rx/of (commit-persisted entry)))

                   (or (= :failed outcome) (= :reconcile @decision))
                   (rx/of (reconcile))

                   :else (rx/empty)))}))))))))

(defn- begin-opt-in-settlement
  [{:keys [id file-id] :as entry} {:keys [answer] :as result}]
  (ptk/reify ::begin-opt-in-settlement
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:persistence :index id]
                        #(cond-> % (not (:confirmed %)) (assoc ::save-result result))))

    ptk/WatchEvent
    (watch [_ state stream]
      (if (and (get-in state [:persistence :index id])
               (not (stale-commit? state entry)))
        (rx/merge
         (->> (rx/timer unseen-changes-timeout-ms)
              (rx/take-until
               (rx/filter #(or (and (ptk/type? ::commit-persisted %) (= id (:id @%)))
                               (ptk/type? ::dw/finalize-workspace %)
                               (ptk/type? ::dw/initialize-workspace %))
                          stream))
              (rx/mapcat
               (fn [_]
                 (rx/of
                  (ptk/reify ::settlement-timeout
                    ptk/WatchEvent
                    (watch [_ state _]
                      (when-let [entry (waiting-save state file-id)]
                        (when (and (= id (:id entry)) (not (:confirmed entry)))
                          (rx/of (reconcile-file file-id
                                                 {:minimum (or (:saved-revn answer) (:revn answer))
                                                  :entry-id id}))))))))))
         (rx/of (settle-opt-in id)))
        (rx/of (commit-persisted entry true))))))

(defn- abandon-view-reconciliations
  "An opening owns its reads, not its edits. Confirmed writes are not
  replayed when the next opening reads the file from the server."
  []
  (let [settled (volatile! nil)]
    (ptk/reify ::abandon-view-reconciliations
      ptk/UpdateEvent
      (update [_ state]
        (let [{:keys [queue index]} (:persistence state)
              entry (get index (peek queue))]
          (when (or (::save-result entry) (:confirmed entry))
            (vreset! settled entry))
          (-> state
              (protect-unsettled-undo @settled)
              (mark-confirmed (:id @settled))
              (update :persistence dissoc :file-order :reconciliations :preview-effects))))

      ptk/WatchEvent
      (watch [_ _ _]
        (if-let [entry @settled]
          (rx/of (commit-persisted entry))
          (rx/empty))))))

(defn- discard-commit
  [commit-id]
  (ptk/reify ::discard-commit
    ptk/UpdateEvent
    (update [_ state]
      (update state :persistence (fn [pstate]
                                   (-> pstate
                                       (update :queue (fn [queue]
                                                        (if (= commit-id (peek queue))
                                                          (pop queue)
                                                          (throw (ex-info "invalid state" {})))))
                                       (update :index dissoc commit-id)
                                       (pcm/keep-saved-commits (get-in pstate [:index commit-id]))
                                       (pcm/prune-remote-changes (get-in pstate [:index commit-id :file-id]))
                                       (assoc :last-progress-at (inst-ms (ct/now)))
                                       (cond-> (= :retrying (:status pstate)) (assoc :status :saving))
                                       (dissoc :stall-reported :failing-since :recovering
                                               :slow-failures :attempts :retry-token
                                               :retry-for :retry-after-read)))))))

(defn- without-commits
  "`buffer` without `commits`."
  [buffer commits]
  (let [ids (into #{} (map :id) commits)]
    (into [] (remove #(contains? ids (:id %))) buffer)))

(defn- buffer-commit
  "Keeps a local commit in the buffer until the next flush."
  [commit]
  (ptk/reify ::buffer-commit
    ptk/UpdateEvent
    (update [_ state]
      (let [number (get-in state [:persistence :remote-seq] 0)]
        (update-in state [:persistence :buffer] (fnil conj [])
                   (cond-> (assoc commit :remote-seq number)
                     (contains? (:tags commit) ::compensation)
                     (assoc :compensation true)))))))

(defn- append-commit
  "Event used internally to append the current change to the
  persistence queue."
  [{:keys [id] :as commit}]
  (let [run-id (uuid/next)]
    (ptk/reify ::append-commit
      ptk/UpdateEvent
      (update [_ state]
        (log/trc :hint "append-commit" :method "update" :commit-id (dm/str id))
        (update state :persistence
                (fn [pstate]
                  (-> pstate
                      (cond-> (not= :error (:status pstate))
                        (update :run-id d/nilv run-id))
                      (update :buffer without-commits (:commits commit))
                      (update :queue queue-conj id)
                      (update :index assoc id commit)))))

      ptk/WatchEvent
      (watch [_ state _]
        (let [pstate (:persistence state)]
          ;; A `:retrying` episode keeps its run id, so a new edit only
          ;; joins the queue: the live runner sends it after the head, and
          ;; resends stay on the backoff schedule and its attempt budget.
          (when (and (not= :error (:status pstate))
                     (= run-id (:run-id pstate)))
            (rx/of (update-status :saving)
                   (run-persistence-task))))))))

(defn- drop-file-commits
  "Removes every queued commit belonging to `file-id`, together with its
  index entry."
  [pstate file-id]
  (if-not file-id
    pstate
    (let [dropped (into #{}
                        (keep (fn [commit-id]
                                (when (= file-id (get-in pstate [:index commit-id :file-id]))
                                  commit-id)))
                        (:queue pstate))]
      (if (empty? dropped)
        pstate
        (-> pstate
            (update :queue #(into (empty %) (remove dropped) %))
            (update :index #(apply dissoc % dropped)))))))

(defn- notify-failure?
  "False for a failed attempt of the slow cycle: it repeats a failure the
  user was already told about."
  [pstate]
  (not (and (:recovering pstate)
            (slow-retry-pending? pstate))))

(defn- save-failed-event
  "Audit event for a save failure the user is told about, so failed saves
  can be counted."
  [{:keys [error queue] :as pstate}]
  (ev/event {::ev/name "save-failed"
             :cause-type (:cause-type error)
             :code (:code error)
             :retrying (slow-retry-pending? pstate)
             :queued-commits (count queue)}))

(defn- end-stopped-slow-cycle
  "Clears `:recovering` when the slow cycle no longer resends the failure:
  the limit of slow failures is reached or the retry window closed."
  [pstate]
  (cond-> pstate
    (not (slow-retry-pending? pstate))
    (dissoc :recovering)))

(defn- persistence-failed
  [commit-id cause]
  (ptk/reify ::persistence-failed
    ptk/UpdateEvent
    (update [_ state]
      (let [data (ex-data cause)]
        (update state :persistence
                (fn [pstate]
                  (-> pstate
                      (assoc :status :error
                             :error (assoc data
                                           :type :persistence
                                           :code (:code data :save-failed)
                                           :cause-type (:type data)
                                           :commit-id commit-id
                                           :hint (ex-message cause)
                                           ::errors/handled? true))
                      (update :failing-since d/nilv (inst-ms (ct/now)))
                      (dissoc :run-id :last-progress-at :stall-reported
                              :attempts :retry-token :retry-for :retry-after-read)
                      (end-stopped-slow-cycle))))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [pstate (:persistence state)]
        (rx/merge
         ;; The terminal toast replaces the reconnect notice, hidden by tag.
         (rx/of (ptk/data-event ::error cause)
                (ntf/hide :tag reconnecting-tag))
         (if (notify-failure? pstate)
           (rx/of (save-failed-event pstate))
           (rx/empty))
         ;; A transport failure that outlasts the backoff can still pass, so
         ;; the queue keeps trying at a slow pace.
         (if (and (not (:suspended pstate))
                  (slow-retry-pending? pstate))
           (slow-retry-cycle stream)
           (rx/empty)))))

    ptk/EffectEvent
    (effect [_ state _]
      ;; Notifies without the global handlers, which may reload the file or
      ;; navigate away and lose the retained changes.
      (let [pstate (:persistence state)]
        (when (notify-failure? pstate)
          (errors/flash-persistence cause
                                    :retrying (slow-retry-pending? pstate)
                                    :busy (busy-server-error? (ex-data cause))))))))

(defn- persistence-transient-failure
  "Transient save failure: the head commit stays queued and a retry is
  scheduled with backoff instead of parking the save in `:error`. Once the
  budget (`retry-delays-ms`) is exhausted, the commit has `max-slow-failures`
  slow failures, or the failed attempt was one of the slow cycle, the
  failure falls through to the `persistence-failed` path, which schedules
  the next slow attempt while `slow-retry-pending?`. While retries are suspended
  nothing is scheduled: the episode waits for a file to be opened again.
  `elapsed-ms` is how long the failed attempt took."
  [commit-id cause elapsed-ms]
  (ptk/reify ::persistence-transient-failure
    ptk/UpdateEvent
    (update [_ state]
      ;; Always counts (even past the budget): the watch routes on the
      ;; stored count, so it must read — never recompute — attempts.
      (let [attempts (inc (dm/get-in state [:persistence :attempts] 0))]
        (update state :persistence
                (fn [pstate]
                  (-> pstate
                      (assoc :status :retrying
                             :attempts attempts
                             :retry-token (uuid/next)
                             :retry-for commit-id
                             :last-progress-at (inst-ms (ct/now)))
                      (update :failing-since d/nilv (inst-ms (ct/now)))
                      (cond-> (>= elapsed-ms slow-failure-ms)
                        (update :slow-failures (fnil inc 0)))
                      (dissoc :stall-reported :retry-after-read))))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [attempts recovering suspended slow-failures]} (:persistence state)]
        (cond
          (or recovering
              (> attempts (count retry-delays-ms))
              (>= (or slow-failures 0) max-slow-failures))
          (rx/of (persistence-failed commit-id cause))

          suspended
          (rx/empty)

          :else
          (rx/merge
           ;; One notice per episode: shown on the first attempt,
           ;; re-showing would only replace the identical toast.
           (when (= 1 attempts)
             (rx/of (ntf/show {:content (if (busy-server-error? (ex-data cause))
                                          (tr "errors.save-retrying-busy")
                                          (tr "errors.save-retrying"))
                               :type :toast
                               :level :warning
                               :tag reconnecting-tag})))
           (let [delay-ms (nth retry-delays-ms (dec attempts))
                 token    (dm/get-in state [:persistence :retry-token])]
             (->> (rx/timer delay-ms)
                  (rx/map (fn [_] (persist-commit commit-id {:token token})))))))))))

(defn- commit-persisted
  ([commit] (commit-persisted commit false))
  ([commit without-view?]
   (ptk/reify ::commit-persisted
     IDeref
     (-deref [_] commit)

     ptk/UpdateEvent
     (update [_ state]
       ;; Keep the acknowledgment even if the queue runner has stopped.
       (let [path [:persistence :index (:id commit)]]
         (cond-> state
           without-view? (protect-unsettled-undo (get-in state path))
           :always (d/update-in-when path assoc ::acknowledged true)))))))

(defn- settle-replaced-head
  [file-id]
  (ptk/reify ::settle-replaced-head
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [queue index]} (:persistence state)
            entry (get index (peek queue))]
        (if (and (:confirmed entry) (::acknowledged entry))
          (rx/of (commit-persisted entry))
          (if-let [retry (retry-after-file-read state file-id)]
            (rx/of retry)
            (rx/empty)))))))

(def rejected-tag
  "Tag of the notice that changes to the open file were rejected."
  :persistence-rejected)

(def ^:private other-file-rejected-tag
  "Tag of the notice that changes to a file not open were rejected."
  :persistence-rejected-other-file)

(def file-repaired-tag
  "Tag of the notice that the backend repaired the open file."
  :file-repaired)

(def repaired-rejected-tag
  "Tag of the notice that tells both of a repair and of rejected changes."
  :file-repaired-rejected)

(def ^:private rejected-timeout-ms
  "How long the rejected changes notice stays; the report is sent anyway."
  10000)

(defn notify-repaired
  "Tells the user the backend repaired the open file. The store holds one
  toast, so a notice of rejected changes to that file still on screen is
  folded into it, keeping its report link."
  []
  (ptk/reify ::notify-repaired
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [tag links]} (:notification state)]
        (rx/of
         (ntf/show
          (if (contains? #{rejected-tag repaired-rejected-tag kept-rejected-tag} tag)
            (cond-> {:content (if (= kept-rejected-tag tag)
                                (str (tr "workspace.notifications.file-repaired") " "
                                     (tr "errors.save-rejected-kept-saved-changes"))
                                (tr "workspace.notifications.file-repaired-changes-undone"))
                     :type :toast
                     :level :warning
                     :timeout nil
                     :tag (if (= kept-rejected-tag tag) tag repaired-rejected-tag)}
              (seq links)
              (assoc :links links))
            {:content (tr "workspace.notifications.file-repaired")
             :type :toast
             :level :info
             :timeout nil
             :tag file-repaired-tag})))))))

(defn- notify-file-repairs
  "Announces repairs independently of applying their changes."
  [{:keys [file-id load-id vern]} repairs covered-only?]
  (let [show? (volatile! false)]
    (ptk/reify ::notify-file-repairs
      ptk/UpdateEvent
      (update [_ state]
        (let [file  (get-live-file state file-id)
              order (get-file-order state file-id)]
          (if (and (= file-id (:current-file-id state))
                   (= load-id (:workspace-load-id state))
                   (= vern (:vern order))
                   (some? (:data file)))
            (let [unannounced (->> repairs
                                   (remove #(porder/announced-repair? order %))
                                   (filter #(or (not covered-only?)
                                                (porder/recovered-repair? order %))))]
              (if (seq unannounced)
                (do (vreset! show? true)
                    (assoc-in state [:persistence :file-order file-id]
                              (porder/announce-repairs order unannounced)))
                state))
            state)))

      ptk/WatchEvent
      (watch [_ _ _]
        (if @show?
          (rx/of (notify-repaired))
          (rx/empty))))))

(defn- rejection-notice
  "Text, tag and timeout of the notice that the changes `rejected` in a
  save of `file-id` were undone. A file other than the open one is named;
  a repair notice of the open file still on screen is folded into it."
  [state file-id {:keys [preexisting-damage]} {:keys [kept-saved]}]
  (let [file-name (dm/get-in state [:files file-id :name])
        current?  (= file-id (:current-file-id state))
        repaired? (contains? #{file-repaired-tag repaired-rejected-tag}
                             (dm/get-in state [:notification :tag]))]
    (cond
      (seq kept-saved)
      {:hint    (if (and current? repaired?)
                  (str (tr "workspace.notifications.file-repaired") " "
                       (tr "errors.save-rejected-kept-saved-changes"))
                  (tr "errors.save-rejected-kept-saved-changes"))
       :tag     (if current? kept-rejected-tag other-file-rejected-tag)
       :timeout nil}

      (and (not current?) (some? file-name))
      {:hint    (tr "errors.save-rejected-changes-undone-in-file" file-name)
       :tag     other-file-rejected-tag
       :timeout rejected-timeout-ms}

      (and current? repaired?)
      {:hint    (tr "workspace.notifications.file-repaired-changes-undone")
       :tag     repaired-rejected-tag
       :timeout nil}

      (and current? preexisting-damage)
      {:hint    (tr "errors.save-rejected-damaged-data")
       :tag     rejected-tag
       :timeout rejected-timeout-ms}

      :else
      {:hint    (tr "errors.save-rejected-changes-undone")
       :tag     (if current? rejected-tag other-file-rejected-tag)
       :timeout rejected-timeout-ms})))

(defn- discard-path-edit
  "Forgets the path editor's state for `id`, so leaving the edition saves
  nothing from it."
  [id]
  (ptk/reify ::discard-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :edit-path] dissoc id))))

(defn- prune-removed-shapes
  "Deselects the shapes missing from the current page, closing the color
  picker that edits them, and leaves edition mode if the edited one is
  missing. A path the rollback changed (`edited` is the edited shape before
  it) leaves edition too: the editor works on its own copy of the shape,
  which still holds the reverted content. Text editors stay open and show
  the reverted text."
  [edited]
  (ptk/reify ::prune-removed-shapes
    ptk/UpdateEvent
    (update [_ state]
      (let [objects  (dsh/lookup-page-objects state)
            selected (dm/get-in state [:workspace-local :selected])
            kept     (some->> selected (into (empty selected) (filter #(contains? objects %))))]
        (cond-> state
          (some? selected)
          (assoc-in [:workspace-local :selected] kept)

          (and (not= (count selected) (count kept))
               (= :colorpicker (dm/get-in state [::modal/modal :type])))
          (dissoc ::modal/modal))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [edition (dm/get-in state [:workspace-local :edition])
            shape   (get (dsh/lookup-page-objects state) edition)]
        (cond
          (nil? edition)
          (rx/empty)

          (nil? shape)
          (rx/of (dwe/clear-edition-mode))

          (and (path.state/editing? state)
               (not= edited shape))
          (rx/of (discard-path-edit edition)
                 (dwe/clear-edition-mode))

          :else
          (rx/empty))))))

(defn- compensate-commits
  "Undoes the saved `commits` of `file-id`, newest first. A loaded file gets
  a commit, applied locally; the undo of a file that is not loaded goes to
  the queue as is."
  [origin state file-id commits]
  (let [changes (pcm/compensation-changes commits)]
    (if (dsh/lookup-file-data state file-id)
      (dch/commit-changes (assoc changes
                                 :origin origin
                                 :file-id file-id
                                 :save-undo? false
                                 :tags #{::compensation}
                                 ;; Its own group, never that of an open undo transaction
                                 :undo-group (uuid/next)
                                 ;; Rollbacks also run while saves fail
                                 :ignore-save-lock true))
      (let [{:keys [file-revn file-vern features]} (peek commits)]
        (append-commit
         (pcm/queue-entry
          [(assoc changes
                  :id (uuid/next)
                  :created-at (ct/now)
                  :origin (ptk/type origin)
                  :file-id file-id
                  :file-revn file-revn
                  :file-vern file-vern
                  :features features
                  :compensation true
                  :remote-seq (dm/get-in state [:persistence :remote-seq] 0))]))))))

(defn- reloaded-s
  "Ends once the reload that follows a failed rebase of `file-ids` has put
  the server data of those files in the state."
  [stream current-file-id file-ids]
  (let [loaded? (if (every? #(= current-file-id %) file-ids)
                  (ptk/type? ::dw/bundle-fetched)
                  (ptk/type? ::dw/all-libraries-resolved))]
    (->> stream
         (rx/filter loaded?)
         (rx/take 1)
         (rx/take-until (rx/filter (ptk/type? ::dw/finalize-workspace) stream)))))

(defn- notify-rolled-back
  "Emits `::rolled-back` with `targets` once the reverted data is in the
  state: at once, or after the reload when a rebase of `rebases` failed.
  The reload replaces the whole file data, so other shapes may change too."
  [stream state rebases targets]
  (rx/defer
   (fn []
     (let [failed (into #{} (comp (map deref) (filter :failed) (map :file-id)) rebases)
           event  (ptk/data-event ::rolled-back targets)]
       (if (empty? failed)
         (rx/of event)
         (rx/map (constantly event) (reloaded-s stream (:current-file-id state) failed)))))))

(defn arm-rollback-failure
  "Arms a one-shot debug fault for a local rollback of the given pending
  commits. Neither the pending commits nor their save request changes."
  [file-id commit-ids]
  (ptk/reify ::arm-rollback-failure
    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::rollback-failure {:file-id file-id :commit-ids commit-ids}))))

(defn- apply-rollback-plan
  "Events that revert the local data as `plan` says: pending commits are
  undone, saved ones undone by a new commit, and their groups leave the
  undo history."
  [origin state stream {:keys [files compensate groups undo-index reconcile]}
   {:keys [entry-id saved-file-id minimum]}]
  (let [{:keys [file-id commit-ids] :as fault} (::rollback-failure state)
        fail?   (and fault
                     (dsh/lookup-file-data state file-id)
                     (some #(contains? commit-ids (:id %))
                           (get-in files [file-id :reverted])))
        immediate-files (apply dissoc files reconcile)
        immediate-targets (pcm/rollback-targets {:files immediate-files :compensate compensate})
        rebases (mapv (fn [[fid plan]]
                        (dch/rebase-commits
                         fid (cond-> plan
                               (and fail? (= file-id fid))
                               (update-in [:undo 0 :undo-changes] conj {:type :debug-fail}))))
                      immediate-files)]
    (rx/concat
     (if fail?
       (rx/of #(cond-> % (= fault (::rollback-failure %)) (dissoc ::rollback-failure)))
       (rx/empty))
     (rx/from rebases)
     (rx/from (map (fn [[fid commits]] (compensate-commits origin state fid commits))
                   (group-by :file-id compensate)))
     (rx/of (dwu/forget-undo-groups groups undo-index))
     (if (get-preview-file-id state)
       (rx/empty)
       (rx/of (prune-removed-shapes (get (dsh/lookup-page-objects state)
                                         (dm/get-in state [:workspace-local :edition])))))
     (rx/from (map (fn [file-id]
                     (let [targets (pcm/rollback-targets {:files (select-keys files [file-id])})]
                       (reconcile-file file-id
                                       (cond-> {:rollback-targets (when (seq (:file-ids targets)) targets)}
                                         (= file-id saved-file-id)
                                         (assoc :entry-id entry-id :minimum minimum)))))
                   reconcile))
     (if (seq (:file-ids immediate-targets))
       (notify-rolled-back stream state rebases immediate-targets)
       (rx/empty)))))

(defn- rejection-base-proven?
  [state file-id {:keys [answer base-proven sent-revn session-id]}]
  (and base-proven
       (nil? (get-reconciliation state file-id))
       (let [revisions (if (:saved-revn answer)
                         (porder/save-revisions sent-revn session-id answer)
                         (porder/rejected-base sent-revn answer))]
         (and revisions
              (= :apply (porder/save-decision (get-file-order state file-id) revisions))))))

(defn- rollback-rejected
  "Reverts the commits `rejected` in the save of `entry`, the later commits
  of its file and the rest of their undo groups. Pending commits are undone
  and dropped, saved ones undone by a new commit; the groups leave the undo
  history and the user gets a warning."
  [{:keys [id file-id] :as entry} rejected {:keys [answer load-id] :as request}]
  (let [plan (volatile! nil)]
    (ptk/reify ::rollback-rejected
      ptk/UpdateEvent
      (update [_ state]
        ;; A save made on a version a restore replaced: the reloaded data
        ;; holds none of it, so there is nothing to revert
        (if (stale-commit? state entry)
          state
          (let [rejected (cond-> rejected
                           (or (not= load-id (:workspace-load-id state))
                               (and (contains? answer :lagged)
                                    (not (rejection-base-proven? state file-id request))))
                           (assoc :uncertain true))
                plan'    (pcm/rollback-plan (:persistence state) entry
                                            (assoc rejected :preview-file-id (get-preview-file-id state)))]
            (vreset! plan plan')
            (update state :persistence pcm/without-rolled-back entry plan'))))

      ptk/WatchEvent
      (watch [it state stream]
        (if-let [{:keys [files groups compensate kept-saved] :as plan} @plan]
          (rx/concat
           (rx/of (ptk/data-event ::save-rejected
                                  {:undo-groups groups
                                   :file-ids (into (set (keys files)) (map :file-id)
                                                   (concat compensate kept-saved))}))
           (apply-rollback-plan it state stream plan
                                {:entry-id id :saved-file-id file-id
                                 :minimum (or (:saved-revn answer) (:revn answer))}))
          (rx/empty)))

      ptk/EffectEvent
      (effect [_ state _]
        (when-let [plan @plan]
          (let [{:keys [error commit-ids]} rejected
                {:keys [hint tag timeout]} (rejection-notice state file-id rejected plan)]
            (errors/flash :cause (ex-info "The server rejected some changes"
                                          (merge error
                                                 {:file-id file-id
                                                  :commit-ids commit-ids}
                                                 (pcm/rollback-summary plan)))
                          :type :unhandled
                          :level :warning
                          :timeout timeout
                          :tag tag
                          :report-link? true
                          :hint hint)))))))

(defn- update-file-request
  "Issues the `update-file-v2` request, tracked as active for its lifetime."
  [request-id params]
  (rx/create
   (fn [subscriber]
     (swap! active-requests conj request-id)
     (let [source       (try
                          (rp/cmd! :update-file-v2 params)
                          (catch :default cause
                            (rx/throw cause)))
           subscription (.subscribe source subscriber)]
       (fn []
         (swap! active-requests disj request-id)
         (rx/dispose! subscription))))))

(defn- attempt-state
  "Classifies what should happen with a queued commit before sending it.
  The attempt stamp and the send decision both read this, so a commit is
  only ever stamped with a request that is actually going to be sent."
  [state commit-id]
  (let [commit (dm/get-in state [:persistence :index commit-id])]
    (cond
      (= :error (dm/get-in state [:persistence :status]))  :halted
      (nil? commit)                                        :missing-commit
      (::acknowledged commit)                              :acknowledged
      (contains? @active-requests (::request-id commit))   :in-flight
      ;; Saved, waiting for the changes stored before it
      (or (::awaiting commit)
          (::save-result commit)
          (:confirmed commit))                            :in-flight
      (get-reconciliation state (:file-id commit))        :waiting-for-read
      (or (empty? (:commits commit))
          (stale-commit? state commit))                    :stale
      (not (dm/get-in state [:permissions :can-edit]))     :permission-denied
      :else                                                :ready)))

(defn- save-commits
  "Projects the commits of a queue entry onto the save contract. The
  backend combines the undo of the accepted commits in reverse order."
  [commits]
  (mapv (fn [{:keys [id redo-changes undo-changes]}]
          {:commit-id id
           :redo-changes (vec redo-changes)
           :undo-changes (vec undo-changes)})
        commits))

(defn- save-attempt-failed
  "Routes a failed attempt to send `commit-id`, which took `elapsed-ms`. A
  commit made on a version a restore replaced is dropped instead: the
  reloaded file does not hold it and no resend can save it."
  [commit-id cause elapsed-ms]
  (ptk/reify ::save-attempt-failed
    ptk/UpdateEvent
    (update [_ state]
      (let [data (ex-data cause)]
        (if (and (transient-error? data)
                 ;; Admission rate limits run before the command. A SQL
                 ;; timeout can instead come from audit after commit.
                 (not= :rate-limit (or (:cause-type data) (:type data))))
          (d/update-in-when state [:persistence :index commit-id] assoc ::uncertain true)
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [commit (dm/get-in state [:persistence :index commit-id])]
        (rx/of (cond
                 (and (some? commit) (stale-commit? state commit))
                 (commit-persisted commit)

                 (transient-error? (ex-data cause))
                 (persistence-transient-failure commit-id cause elapsed-ms)

                 :else
                 (persistence-failed commit-id cause)))))))

(defn- send-queued-commit
  "Sends one queued commit and maps its outcome to persistence events."
  [request-id {:keys [session-id workspace-load-id] :as state}
   {:keys [id file-id file-revn file-vern features commits] :as commit}]
  (let [sent-at     (inst-ms (ct/now))
        sent-revn   (max file-revn (get @revn-data file-id 0)
                         (:revn (get-live-file state file-id) 0))
        base-proven (porder/proven-base? (get-file-order state file-id) sent-revn)
        params      {:id file-id
                     :revn sent-revn
                     :vern file-vern
                     :session-id session-id
                     :features features
                     :repair-in-response true
                     :commits (save-commits commits)}]
    ;; UI read-only mode does not invalidate already queued edits.
    (->> (update-file-request request-id params)
         ;; Fails an unanswered request as a transient network error, so the
         ;; retry path resends it instead of blocking the queue.
         (rx/timeout save-request-timeout-ms
                     (rx/throw (ex-info "The save request timed out"
                                        {:type :network
                                         :code :save-request-timeout
                                         :file-id file-id})))
         (rx/take 1)
         ;; A response that carries no revision, including one that never
         ;; arrived, is treated as a failed save rather than a saved file.
         (rx/if-empty nil)
         (rx/mapcat (fn [{:keys [revn saved-revn rejected lagged] :as answer}]
                      (if (and (int? revn) (<= 0 revn))
                        (let [uncertain (::uncertain commit)
                              opt-in?   (or (some? saved-revn) (and uncertain rejected))
                              result    {:answer answer :session-id session-id :sent-revn sent-revn
                                         :base-proven base-proven :load-id workspace-load-id}]
                          (rx/concat
                           (if (and (not opt-in?) (not (and rejected (contains? answer :lagged))))
                             (rx/of (update-file-revn file-id revn {:vern file-vern
                                                                    :load-id workspace-load-id}))
                             (rx/empty))
                           ;; Classify the earlier attempt before planning any
                           ;; compensation for the rejected suffix.
                           (if (some? rejected)
                             (rx/of (rollback-rejected commit (assoc rejected :uncertain uncertain) result))
                             (rx/empty))
                           (rx/of
                            (if opt-in?
                              (begin-opt-in-settlement commit result)
                              (settle-legacy-save commit lagged)))))
                        (rx/throw (ex-info "The save response has no valid revision"
                                           {:type :persistence
                                            :code :invalid-save-response
                                            :file-id file-id})))))
         (rx/catch (fn [cause]
                     (rx/of (save-attempt-failed id cause (- (inst-ms (ct/now)) sent-at))))))))

(defn- persist-commit
  "Sends the queued commit, stamping the attempt first. A commit whose
  earlier request is no longer in flight can be sent again, even when
  the outcome of that request is uncertain. The IDs stay the same, but
  the server does not use them to deduplicate requests.
  Carries an optional `:token`: retry timers pass the episode token, and a
  stale token (superseded episode) stays silent instead of sending or
  failing."
  [commit-id & {:keys [token]}]
  (let [request-id (uuid/next)]
    (ptk/reify ::persist-commit
      ptk/UpdateEvent
      (update [_ state]
        (let [{:keys [retry-for retry-token]} (:persistence state)]
          (if (or (nil? token) (= token retry-token))
            (case (attempt-state state commit-id)
              ;; Stamp the attempt before any I/O, so a request in flight is
              ;; never sent twice in parallel.
              :ready (-> state
                         (assoc-in [:persistence :index commit-id ::request-id] request-id)
                         (update :persistence dissoc :retry-after-read))
              :waiting-for-read
              (cond-> state
                (and token (= commit-id retry-for))
                (assoc-in [:persistence :retry-after-read] token))
              state)
            state)))

      ptk/WatchEvent
      (watch [_ state _]
        (let [commit (dm/get-in state [:persistence :index commit-id])
              fail   (fn [code hint]
                       (rx/of (persistence-failed commit-id
                                                  (ex-info hint {:type :persistence
                                                                 :code code
                                                                 :commit-id commit-id
                                                                 :file-id (:file-id commit)
                                                                 :unsaved-commits (count-unsaved-commits state)}))))]
          (if (and (some? token)
                   (not= token (dm/get-in state [:persistence :retry-token])))
            ;; Stale retry timer: its episode was superseded. Stay silent.
            (rx/empty)
            (case (attempt-state state commit-id)
              :halted            (rx/empty)
              :missing-commit    (fail :missing-commit "A queued save has no change data")
              :acknowledged      (rx/of (commit-persisted commit))
              ;; The original request or the file read owns the next step.
              (:in-flight :waiting-for-read) (rx/empty)
              ;; Made on a version a restore replaced, or already emptied by it
              :stale             (rx/of (commit-persisted commit))
              :permission-denied (fail :save-permission-denied "Edit permission was lost before changes could be saved")
              :ready             (send-queued-commit request-id state commit))))))))


(defn- run-persistence-task
  []
  (ptk/reify ::run-persistence-task
    ptk/WatchEvent
    (watch [_ state stream]
      (let [queue (-> state :persistence :queue)]
        (cond
          (= :error (dm/get-in state [:persistence :status]))
          (rx/empty)

          (seq queue)
          (let [commit-id (peek queue)
                stoper-s (rx/merge
                          (rx/filter (ptk/type? ::run-persistence-task) stream)
                          (rx/filter (ptk/type? ::error) stream))]

            (log/dbg :hint "run-persistence-task" :commit-id (dm/str commit-id))
            (->> (rx/merge
                  (->> stream
                       (rx/filter (ptk/type? ::commit-persisted))
                       (rx/map deref)
                       (rx/filter #(= commit-id (:id %)))
                       (rx/take 1)
                       (rx/mapcat (fn [_]
                                    ;; A save that lands shows the connection
                                    ;; is back: the failure warning goes away.
                                    (rx/of (discard-commit commit-id)
                                           (ntf/hide :tag errors/persistence-failed-tag)
                                           (run-persistence-task)))))
                  (rx/of (persist-commit commit-id)))
                 (rx/take-until stoper-s)))

          :else
          (rx/of (update-status :saved)
                 (ntf/hide :tag reconnecting-tag)))))))

(defn- resume-persistence
  "Starts the queue again. A `recovering` resume is an attempt of the slow
  cycle, which sends once instead of starting a backoff episode."
  ([] (resume-persistence false))
  ([recovering]
   (ptk/reify ::resume-persistence
     ptk/UpdateEvent
     (update [_ state]
       (update state :persistence
               (fn [pstate]
                 (-> pstate
                     (dissoc :error :attempts :retry-token :retry-for :retry-after-read)
                     (assoc :run-id (uuid/next)
                            :status :saving
                            :recovering recovering)
                     (update :last-progress-at d/nilv (inst-ms (ct/now)))))))
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of (run-persistence-task))))))

(defn- slow-retry-cycle
  "Resumes a failed queue after `slow-retry-delay-ms`, unless the queue is
  resumed some other way, persistence restarts or the workspace closes."
  [stream]
  (let [stopper-s (rx/merge
                   (rx/filter (ptk/type? ::resume-persistence) stream)
                   (rx/filter (ptk/type? ::initialize-persistence) stream)
                   (rx/filter (ptk/type? ::dw/finalize-workspace) stream))]
    (->> (rx/timer slow-retry-delay-ms)
         (rx/map (fn [_] (resume-persistence true)))
         (rx/take-until stopper-s))))

(defn- suspend-retries
  "Stops retrying when the workspace closes. A pending backoff timer goes
  stale, and no backoff or slow cycle is armed until a file is opened
  again. Queued edits stay queued and a request in flight still lands."
  []
  (ptk/reify ::suspend-retries
    ptk/UpdateEvent
    (update [_ state]
      (update state :persistence
              #(-> %
                   (assoc :suspended true)
                   (dissoc :retry-token :retry-after-read))))))

(defn- restore-retries
  "Lets failed saves be retried again once a file is opened."
  []
  (ptk/reify ::restore-retries
    ptk/UpdateEvent
    (update [_ state]
      (update state :persistence dissoc :suspended))))

(defn- release-failed-file
  "Drops the queued commits of the file whose save failed terminally.

  This runs when the user opens a file again, not at failure time. The
  failure itself stays visible, because `wait-persisted-or-error` must keep
  rejecting: callers use it to avoid snapshotting a file whose edits were
  never saved. What must not survive is the halt. Left in place, the failed
  commits sit at the head of the queue forever and, because the queue is
  shared, they also block the pending edits of every other file.

  With `revert?` the file is still open with the released edits applied,
  so they are rolled back as a rejection would be: undone on screen and
  cut from the undo history."
  [revert?]
  (let [plan (volatile! nil)]
    (ptk/reify ::release-failed-file
      ptk/UpdateEvent
      (update [_ state]
        (let [pstate    (:persistence state)
              commit-id (get-in pstate [:error :commit-id])
              file-id   (get-in pstate [:index commit-id :file-id])
              plan'     (when (and revert? file-id)
                          (pcm/release-plan pstate file-id (get-preview-file-id state)))]
          (vreset! plan plan')
          (assoc state :persistence
                 (-> (if plan'
                       (pcm/without-rolled-back pstate {:file-id file-id} plan')
                       pstate)
                     (drop-file-commits file-id)
                     (pcm/prune-remote-changes file-id)
                     (dissoc :failing-since :slow-failures)))))

      ptk/WatchEvent
      (watch [it state stream]
        (if-let [plan @plan]
          (apply-rollback-plan it state stream plan nil)
          (rx/empty))))))

(defn- queue-needs-a-runner?
  "True when the queue still holds commits that nobody is sending.

  The runner does not survive a subscription teardown, so both
  initialization and recovery hit this. A halted queue is excluded: the
  halt is lifted by `recover-persistence`, which has its own triggers. A
  retrying episode is excluded too: its backoff timer owns the recovery,
  or `recover-persistence` resumes it when retries were suspended."
  [{:keys [queue status]}]
  (and (seq queue)
       (not= :error status)
       (not= :retrying status)))

(defn forget-other-versions
  "Forgets the commits of the freshly loaded `file` made on another version
  (`pcm/without-other-versions`), so no rollback or rebase replays them on
  the loaded data. A pending status with nothing left to save turns saved."
  [state {:keys [id vern]}]
  (update state :persistence
          (fn [pstate]
            (let [pstate (pcm/without-other-versions pstate id vern)]
              (cond-> pstate
                (and (= :pending (:status pstate))
                     (nothing-to-save? pstate))
                (assoc :status :saved))))))

(defn pending-file-changes
  "Queued and buffered edits to restore into a freshly loaded file, oldest
  first. Only edits of the loaded version belong in its data. A
  permanently failed file is excluded because recovery releases its edits."
  [{:keys [persistence]} {:keys [id vern]}]
  (let [{:keys [queue index buffer status error]} persistence
        failed-file-id (get-in index [(:commit-id error) :file-id])
        of-file?       #(and (= id (:file-id %))
                             (= vern (:file-vern %)))]
    (if (and (= :error status)
             (= id failed-file-id)
             (not (slow-retry-pending? persistence)))
      []
      (-> []
          (into (comp (map #(get index %))
                      (remove #(or (:confirmed %) (::acknowledged %)))
                      (filter of-file?)
                      (mapcat :commits)
                      (mapcat :redo-changes))
                queue)
          (into (comp (filter of-file?)
                      (mapcat :redo-changes))
                buffer)))))

(defn- initialize-persistence-state
  "Brings persistence to a coherent starting shape when the workspace
  mounts.

  Deliberately leaves a halted or retrying queue alone. Lifting a halt is
  `recover-persistence`'s job, and it waits for the user to open a file, so
  doing it here would release edits the moment the view mounts rather than
  when the user acts on them."
  []
  (ptk/reify ::initialize-persistence-state
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [queue status run-id buffer]} (:persistence state)]
        (rx/concat
         (cond
           ;; A commit left queued by a previous session, with no runner.
           (queue-needs-a-runner? (:persistence state))
           (rx/of (resume-persistence))

           ;; Nothing queued and nothing in flight: there is nothing to save.
           (and (empty? queue)
                (empty? buffer)
                (not= status :error)
                (or run-id (#{:pending :saving} status)))
           (rx/of (update-status :saved))

           :else
           (rx/empty))
         ;; Queues the commits a previous session left buffered
         (if (seq buffer)
           (rx/of (flush-buffer))
           (rx/empty)))))))

(defn- notify-kept-failure
  "Shows the save failure the queue keeps again, so the count of unsaved
  changes replaces the notice the role change shows."
  []
  (ptk/reify ::notify-kept-failure
    ptk/EffectEvent
    (effect [_ state _]
      (let [error (dm/get-in state [:persistence :error])]
        (errors/flash-persistence (ex-info (:hint error) error))))))

(defn- recover-persistence
  "Lifts a halted queue when a file is opened again or the role changes.

  Leaving the workspace suspends retries (`suspend-retries`), so opening a
  file (`opened-file?`) restores them, resends a retrying episode whose
  backoff was cut short, and sends a failure the slow cycle still covers
  (`slow-retry-pending?`) once more instead of dropping it.

  Every other terminal failure is permanent for that file: permission lost,
  file gone, or changes the server refuses. None of them can succeed on a
  resend, so opening a file releases them instead of replaying them.
  Retaining them until here is deliberate: it is what keeps
  `wait-persisted-or-error` rejecting, which is what stops
  `workspace/versions.cljs` from building a snapshot out of unsaved edits.

  A lost permission is not a temporary condition. The backend raises
  `:not-found` for it, and replaying the save to find that out routes the
  user to the exception page. A role change that leaves the user a viewer
  keeps those edits; the one that brings edit rights back releases them."
  [opened-file?]
  (ptk/reify ::recover-persistence
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [queue status run-id suspended]} (:persistence state)]
        (rx/concat
         (if (and opened-file? suspended)
           (rx/of (restore-retries))
           (rx/empty))
         (cond
           ;; A live retry episode owns its recovery through the backoff
           ;; scheduler; resuming here would bypass the attempt budget.
           ;; A suspended one has no scheduler left.
           (= :retrying status)
           (if (and opened-file? suspended)
             (rx/of (resume-persistence))
             (rx/empty))

           (slow-retry-pending? (:persistence state))
           (rx/of (resume-persistence true))

           ;; A viewer's edits wait for edit rights to come back, still
           ;; queued and on screen.
           (and (not opened-file?)
                (= :save-permission-denied (dm/get-in state [:persistence :error :code]))
                (not (dm/get-in state [:permissions :can-edit])))
           (rx/of (notify-kept-failure))

           ;; An opened file was loaded without the released edits; one
           ;; still open shows them, so they are reverted.
           (= :error status)
           (rx/of (release-failed-file (not opened-file?)) (resume-persistence))

           (queue-needs-a-runner? (:persistence state))
           (rx/of (resume-persistence))

           (and (empty? queue) (or run-id (#{:pending :saving} status)))
           (rx/of (update-status :saved))

           :else
           (rx/empty)))))))

(defn- merge-commit
  "One queue entry per file and version: a save carries one version."
  [buffer]
  (->> (rx/from (group-by (juxt :file-id :file-vern) buffer))
       (rx/map (fn [[_ commits]]
                 (pcm/queue-entry
                  (mapv (fn [commit]
                          (-> commit
                              (update :undo-changes mch/merge-reg-objects)
                              (update :redo-changes mch/merge-reg-objects)))
                        commits))))))

(defn- flush-buffer
  "Queues the buffered commits, merged into one queue entry per file."
  []
  (ptk/reify ::flush-buffer
    ptk/WatchEvent
    (watch [_ state _]
      (if-let [buffer (seq (dm/get-in state [:persistence :buffer]))]
        (->> (merge-commit buffer)
             (rx/map append-commit))
        (rx/empty)))))

(defn- resume-on-online
  "Re-enters the runner for a retrying episode when the browser reports
  connectivity back, instead of waiting out the backoff. Terminal failures
  stay terminal: only a live `:retrying` episode resumes, never one
  suspended outside the workspace."
  []
  (ptk/reify ::resume-on-online
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [status queue suspended]} (:persistence state)]
        (if (and (seq queue) (= :retrying status) (not suspended))
          (rx/of (run-persistence-task))
          (rx/empty))))))

(defn- sync-unload-guard
  "Turns the browser's leave-page warning on while edits are unsaved:
  buffered, queued, or retained after a failed save."
  []
  (ptk/reify ::sync-unload-guard
    ptk/EffectEvent
    (effect [_ state _]
      (if (or (seq (dm/get-in state [:persistence :buffer]))
              (seq (dm/get-in state [:persistence :queue])))
        (swap! st/ongoing-tasks conj :persistence)
        (swap! st/ongoing-tasks disj :persistence)))))

(defn- persist-buffered
  "Forces a save when local commits wait in the buffer."
  []
  (ptk/reify ::persist-buffered
    ptk/WatchEvent
    (watch [_ state _]
      (if (seq (dm/get-in state [:persistence :buffer]))
        (rx/of ::force-persist)
        (rx/empty)))))

(defn leave-signals
  "Emits when the tab is hidden or the window loses focus: another tab or
  app, a plugin iframe, a closed laptop. Empty outside the browser."
  []
  (if (and (exists? js/window) (exists? js/document))
    (rx/merge
     (->> (rx/from-event js/document "visibilitychange")
          (rx/filter #(= "hidden" (.-visibilityState js/document))))
     (rx/from-event js/window "blur"))
    (rx/empty)))

(defn initialize-persistence
  []
  (ptk/reify ::initialize-persistence
    ptk/WatchEvent
    (watch [_ _ stream]
      (log/debug :hint "initialize persistence")
      (let [stoper-s (rx/filter (ptk/type? ::initialize-persistence) stream)

            local-commits-s
            (->> stream
                 (rx/filter dch/commit?)
                 (rx/map deref)
                 (rx/filter #(= :local (:source %)))
                 (rx/filter (complement empty?))
                 (rx/share))

            notifier-s
            (rx/merge
             (->> local-commits-s
                  (rx/debounce 3000)
                  (rx/tap #(log/trc :hint "persistence beat")))
             (->> stream
                  (rx/filter #(= % ::force-persist))))]

        (rx/merge
         (rx/of (initialize-persistence-state))

         (->> stream
              (rx/filter (ptk/type? ::dw/finalize-workspace))
              (rx/map (fn [_] (suspend-retries)))
              (rx/take-until stoper-s))

         (->> stream
              (rx/filter #(or (ptk/type? ::dw/finalize-workspace %)
                              (ptk/type? ::dw/initialize-workspace %)))
              (rx/map (fn [_] (abandon-view-reconciliations)))
              (rx/take-until stoper-s))

         (->> stream
              (rx/filter (ptk/type? ::dw/bundle-fetched))
              (rx/map (fn [event] (settle-replaced-head (:file-id @event))))
              (rx/take-until stoper-s))

         (->> stream
              (rx/filter #(or (ptk/type? ::dc/change-team-role %)
                              (ptk/type? ::dw/workspace-initialized %)))
              (rx/map #(recover-persistence (ptk/type? ::dw/workspace-initialized %)))
              (rx/take-until stoper-s))

         (->> (rx/interval saving-check-interval-ms)
              (rx/map (fn [_] (check-persistence)))
              (rx/take-until stoper-s))

         ;; The browser knows when connectivity returns: re-enter the
         ;; runner for a retrying episode instead of waiting out the
         ;; backoff. No `window` outside the browser (tests, SSR).
         (or (when (exists? js/window)
               (->> (rx/from-event js/window "online")
                    (rx/map (fn [_] (resume-on-online)))
                    (rx/take-until stoper-s)))
             (rx/empty))

         (->> notifier-s
              (rx/map #(ptk/data-event ::persistence-notification))
              (rx/take-until stoper-s))

         ;; Sends the buffered edits when the user leaves the tab or window.
         (->> (leave-signals)
              (rx/map (fn [_] (persist-buffered)))
              (rx/take-until stoper-s))

         ;; Syncs the leave-page warning on every buffer and queue change
         (->> (rx/merge
               (rx/of nil)
               (->> stream
                    (rx/filter #(or (ptk/type? ::buffer-commit %)
                                    (ptk/type? ::append-commit %)
                                    (ptk/type? ::discard-commit %)
                                    (ptk/type? ::rollback-rejected %)
                                    (ptk/type? ::release-failed-file %)
                                    (ptk/type? ::reconcile-file %)
                                    (ptk/type? ::reconciliation-result %)
                                    (ptk/type? ::dw/bundle-fetched %)))))
              (rx/map (fn [_] (sync-unload-guard)))
              (rx/take-until stoper-s))

         ;; Locks the workspace while saves fail and the user can edit
         (->> stream
              (rx/filter #(or (ptk/type? ::update-status %)
                              (ptk/type? ::persistence-failed %)
                              (ptk/type? ::resume-persistence %)
                              (ptk/type? ::discard-commit %)
                              (ptk/type? ::dc/change-team-role %)))
              (rx/map (fn [_] (plk/sync-failure-lock)))
              (rx/take-until stoper-s))

         (->> local-commits-s
              (rx/debounce 200)
              (rx/map (fn [_]
                        (update-status :pending)))
              (rx/take-until stoper-s))

         ;; Buffers local commits; each notification queues the buffer
         (->> local-commits-s
              (rx/map buffer-commit)
              (rx/take-until stoper-s))

         (->> notifier-s
              (rx/map (fn [_] (flush-buffer)))
              (rx/take-until stoper-s)
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: changes watcher"))))

         ;; Here we track all incoming remote commits for maintain
         ;; updated the local state with the file revn
         (->> stream
              (rx/filter dch/commit?)
              (rx/map deref)
              (rx/filter #(= :remote (:source %)))
              (rx/mapcat (fn [{:keys [file-id file-revn redo-changes]}]
                           (rx/of (record-remote-changes file-id file-revn redo-changes)
                                  (update-file-revn file-id file-revn))))
              (rx/take-until stoper-s)))))))
