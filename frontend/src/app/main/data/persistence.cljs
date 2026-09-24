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
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.common :as-alias dc]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as-alias dw]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare ^:private run-persistence-task)
(declare ^:private persist-commit)
(declare ^:private resume-persistence)
(declare ^:private slow-retry-cycle)

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
  "How long a failing queue keeps being sent. Matches how long the backend
  remembers a commit id."
  (* 24 60 60 1000))

(def ^:private saving-stall-timeout-ms (* 5 60 1000))
(def ^:private saving-check-interval-ms 30000)
(def ^:private save-wait-timeout-ms (* 2 60 1000))

(defn terminal-status?
  "True when a persistence snapshot releases waiters: a failed save, or
  a settled (`nil` / `:saved`) empty queue. Anything else — `:pending`,
  `:saving` and the `:retrying` episode — keeps waiting."
  [{:keys [status queue]}]
  (boolean
   (or (= status :error)
       (and (empty? queue)
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

(defn- update-status
  [status]
  (ptk/reify ::update-status
    ptk/UpdateEvent
    (update [_ state]
      (update state :persistence
              (fn [pstate]
                (log/trc :hint "update-status"
                         :from (:status pstate)
                         :to status)
                (let [status (next-status (:status pstate) status)]
                  (cond-> (assoc pstate :status status)
                    (#{:pending :saving} status)
                    (update :last-progress-at d/nilv (inst-ms (ct/now)))

                    (#{:error :saved} status)
                    (dissoc :run-id :last-progress-at :stall-reported))))))))

(def ^:private transient-codes
  "Save failures a later attempt can get past: an unusable save response, and
  a repeat the backend turned away while the save it repeats was running."
  #{:invalid-save-response
    :commit-in-progress})

(defn transient-error?
  "True when a save failure is worth retrying with backoff: a transient
  transport failure (from the shared `repo/retryable-types` set, which says
  nothing about the edits themselves) or one of `transient-codes`.
  Everything else (auth, validation, state) is terminal and keeps the
  `:error` path. Checked against both `:type` and `:cause-type` because
  `persistence-failed` wraps the original cause under `:type :persistence`."
  [{:keys [type cause-type code]}]
  (boolean
   (or (contains? rp/retryable-types type)
       (contains? rp/retryable-types cause-type)
       (contains? transient-codes code))))

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
  "True while a failing queue may still be sent again. Past this the backend
  has forgotten the commit id, so sending it once more could apply the same
  changes twice."
  [pstate]
  (let [since (:failing-since pstate)]
    (or (nil? since)
        (< (- (inst-ms (ct/now)) since) retry-give-up-ms))))

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

(defn- update-file-revn
  [file-id revn]
  (ptk/reify ::update-file-revn
    ptk/UpdateEvent
    (update [_ state]
      (log/dbg :hint "update-file-revn" :file-id (dm/str file-id) :revn revn)
      (dsh/update-file state file-id #(update % :revn max revn)))

    ptk/EffectEvent
    (effect [_ _ _]
      (swap! revn-data update file-id (fnil max 0) revn))))

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
                                       (assoc :last-progress-at (inst-ms (ct/now)))
                                       (dissoc :stall-reported :failing-since :recovering
                                               :attempts :retry-token :retry-for)))))))

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
                      (update :queue queue-conj id)
                      (update :index assoc id commit)))))

      ptk/WatchEvent
      (watch [_ state _]
        (let [pstate (:persistence state)]
          (cond
            ;; A new edit restarts a failed queue from its head, while the
            ;; backend still recognizes the head's commit id and so cannot
            ;; apply it twice.
            (= :error (:status pstate))
            (when (retry-window-open? pstate)
              (rx/of (resume-persistence)))

            ;; A `:retrying` episode keeps its run id, so a new edit only
            ;; joins the queue: the live runner sends it after the head, and
            ;; resends stay on the backoff schedule and its attempt budget.
            (= run-id (:run-id pstate))
            (rx/of (update-status :saving)
                   (run-persistence-task))))))))

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
                              :attempts :retry-token :retry-for))))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       ;; The terminal toast supersedes the reconnect notice; hide it
       ;; explicitly instead of relying on the single-toast replacement.
       (rx/of (ptk/data-event ::error cause)
              (ntf/hide :tag reconnecting-tag))
       ;; A transport failure that outlasts the backoff can still pass, so
       ;; the queue keeps trying at a slow pace.
       (if (and (transient-error? (ex-data cause))
                (retry-window-open? (:persistence state)))
         (slow-retry-cycle stream)
         (rx/empty))))

    ptk/EffectEvent
    (effect [_ state _]
      ;; A failed slow attempt repeats a failure the user was already warned
      ;; about. Report without invoking global handlers that may reload the
      ;; file or navigate away before the user can recover the retained
      ;; changes.
      (when-not (and (dm/get-in state [:persistence :recovering])
                     (transient-error? (ex-data cause)))
        (errors/flash-persistence cause)))))

(defn- persistence-transient-failure
  "Transient save failure: the head commit stays queued and a retry is
  scheduled with backoff instead of parking the save in `:error`. Once the
  budget (`retry-delays-ms`) is exhausted, or when the failed attempt was
  one of the slow cycle, the failure falls through to the terminal
  `persistence-failed` path, which schedules the next slow attempt."
  [commit-id cause]
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
                      (dissoc :stall-reported))))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [attempts recovering]} (:persistence state)]
        (if (or recovering (> attempts (count retry-delays-ms)))
          (rx/of (persistence-failed commit-id cause))
          (rx/merge
           ;; One notice per episode: shown on the first attempt,
           ;; re-showing would only replace the identical toast.
           (when (= 1 attempts)
             (rx/of (ntf/show {:content (tr "errors.save-retrying")
                               :type :toast
                               :level :warning
                               :tag reconnecting-tag})))
           (let [delay-ms (nth retry-delays-ms (dec attempts))
                 token    (dm/get-in state [:persistence :retry-token])]
             (->> (rx/timer delay-ms)
                  (rx/map (fn [_] (persist-commit commit-id {:token token})))))))))))

(defn- commit-persisted
  [commit]
  (ptk/reify ::commit-persisted
    IDeref
    (-deref [_] commit)

    ptk/UpdateEvent
    (update [_ state]
      ;; Keep the acknowledgment even if the queue runner has stopped.
      (d/update-in-when state [:persistence :index (:id commit)]
                        assoc ::acknowledged true))))

(defn- update-file-request
  "Issues the `update-file` request, tracked as active for its lifetime."
  [request-id params]
  (rx/create
   (fn [subscriber]
     (swap! active-requests conj request-id)
     (let [source       (try
                          (rp/cmd! :update-file params)
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
      (not (dm/get-in state [:permissions :can-edit]))     :permission-denied
      :else                                                :ready)))

(defn- send-queued-commit
  "Sends one queued commit and maps its outcome to persistence events."
  [request-id session-id {:keys [id file-id file-revn file-vern changes features] :as commit}]
  (let [params {:id file-id
                :revn (max file-revn (get @revn-data file-id 0))
                :vern file-vern
                :session-id session-id
                :origin (:origin commit)
                :created-at (:created-at commit)
                :commit-id id
                :changes (vec changes)
                :features features}]
    ;; UI read-only mode does not invalidate already queued edits.
    (->> (update-file-request request-id params)
         (rx/take 1)
         ;; A response that carries no revision, including one that never
         ;; arrived, is treated as a failed save rather than a saved file.
         (rx/if-empty nil)
         (rx/mapcat (fn [{:keys [revn]}]
                      (if (and (int? revn) (<= 0 revn))
                        (rx/of (update-file-revn file-id revn)
                               (commit-persisted commit))
                        (rx/throw (ex-info "The save response has no valid revision"
                                           {:type :persistence
                                            :code :invalid-save-response
                                            :file-id file-id})))))
         (rx/catch (fn [cause]
                     (rx/of ((if (transient-error? (ex-data cause))
                               persistence-transient-failure
                               persistence-failed)
                             id cause)))))))

(defn- persist-commit
  "Sends the queued commit, stamping the attempt first. A commit whose
  earlier request is no longer in flight is sent again under the same
  commit id, which the backend applies only once. Carries an optional
  `:token`: retry timers pass the episode token, and a stale token
  (superseded episode) stays silent instead of sending or failing."
  [commit-id & {:keys [token]}]
  (let [request-id (uuid/next)]
    (ptk/reify ::persist-commit
      ptk/UpdateEvent
      (update [_ state]
        (let [token-ok? (or (nil? token)
                            (= token (dm/get-in state [:persistence :retry-token])))]
          (if (and token-ok?
                   (= :ready (attempt-state state commit-id)))
            ;; Stamp the attempt before any I/O, so a request in flight is
            ;; never sent twice in parallel.
            (assoc-in state [:persistence :index commit-id ::request-id] request-id)
            state)))

      ptk/WatchEvent
      (watch [_ state _]
        (let [commit (dm/get-in state [:persistence :index commit-id])
              fail   (fn [code hint]
                       (rx/of (persistence-failed commit-id
                                                  (ex-info hint {:type :persistence
                                                                 :code code
                                                                 :commit-id commit-id
                                                                 :file-id (:file-id commit)}))))]
          (if (and (some? token)
                   (not= token (dm/get-in state [:persistence :retry-token])))
            ;; Stale retry timer: its episode was superseded. Stay silent.
            (rx/empty)
            (case (attempt-state state commit-id)
              :halted            (rx/empty)
              :missing-commit    (fail :missing-commit "A queued save has no change data")
              :acknowledged      (rx/of (commit-persisted commit))
              ;; The replacement runner listens for the original request's result.
              :in-flight         (rx/empty)
              :permission-denied (fail :save-permission-denied "Edit permission was lost before changes could be saved")
              :ready             (send-queued-commit request-id (:session-id state) commit))))))))


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
                                    (rx/of (discard-commit commit-id)
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
                     (dissoc :error :attempts :retry-token :retry-for)
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

(defn- recover-persistence
  []
  (ptk/reify ::recover-persistence
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [queue index status error run-id] :as pstate} (:persistence state)
            commit (get index (peek queue))]
        (cond
          ;; A retrying episode owns its recovery through the backoff
          ;; scheduler; resuming here would bypass the attempt budget.
          (and (seq queue)
               (not= status :retrying)
               (or (not= status :error)
                   (and (= :save-permission-denied (:code error))
                        (retry-window-open? pstate)
                        (= (:file-id commit) (:current-file-id state))
                        (dm/get-in state [:permissions :can-edit]))))
          (rx/of (resume-persistence))

          (and (empty? queue)
               (not= status :error)
               (or run-id (#{:pending :saving} status)))
          (rx/of (update-status :saved)))))))

(def ^:private xf-mapcat-undo
  (mapcat :undo-changes))

(def ^:private xf-mapcat-redo
  (mapcat :redo-changes))

(defn- merge-commit
  [buffer]
  (->> (rx/from (group-by :file-id buffer))
       (rx/map (fn [[_ [item :as commits]]]
                 (let [uchg (into [] xf-mapcat-undo commits)
                       rchg (into [] xf-mapcat-redo commits)]
                   (-> item
                       (assoc :undo-changes uchg)
                       (assoc :redo-changes rchg)
                       (assoc :changes rchg)))))))

(defn- resume-on-online
  "Re-enters the runner for a retrying episode when the browser reports
  connectivity back, instead of waiting out the backoff. Terminal failures
  stay terminal: only a live `:retrying` episode resumes."
  []
  (ptk/reify ::resume-on-online
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [status queue]} (:persistence state)]
        (if (and (seq queue) (= :retrying status))
          (rx/of (run-persistence-task))
          (rx/empty))))))

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
         (rx/of (recover-persistence))

         (->> stream
              (rx/filter #(or (ptk/type? ::dc/change-team-role %)
                              (ptk/type? ::dw/workspace-initialized %)))
              (rx/map (fn [_] (recover-persistence)))
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

         (->> local-commits-s
              (rx/debounce 200)
              (rx/map (fn [_]
                        (update-status :pending)))
              (rx/take-until stoper-s))

         ;; Here we watch for local commits, buffer them in a small
         ;; chunks (very near in time commits) and append them to the
         ;; persistence queue
         (->> local-commits-s
              (rx/take-until stoper-s)
              (rx/buffer-until notifier-s)
              (rx/mapcat merge-commit)
              (rx/map append-commit)
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: changes watcher"))))

         ;; Here we track all incoming remote commits for maintain
         ;; updated the local state with the file revn
         (->> stream
              (rx/filter dch/commit?)
              (rx/map deref)
              (rx/filter #(= :remote (:source %)))
              (rx/mapcat (fn [{:keys [file-id file-revn] :as commit}]
                           (rx/of (update-file-revn file-id file-revn))))
              (rx/take-until stoper-s)))))))
