;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.persistence
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.common :as-alias dc]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as-alias dw]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare ^:private run-persistence-task)

(log/set-level! :warn)

(def revn-data (atom {}))
(defonce ^:private active-requests (atom #{}))
(def queue-conj (fnil conj #queue []))

(def force-persist? #(= % ::force-persist))

(def ^:private saving-stall-timeout-ms (* 5 60 1000))
(def ^:private saving-check-interval-ms 30000)
(def ^:private save-wait-timeout-ms (* 2 60 1000))

(defn wait-persisted-or-error
  "Returns an observable that emits the first terminal persistence status
   (nil | :saved) and completes. Raises when the queue has failed and, with
   a timeout-ms, when persistence does not settle in time."
  ([] (wait-persisted-or-error save-wait-timeout-ms))
  ([timeout-ms]
   (let [base (->> (rx/from-atom refs/persistence {:emit-current-value? true})
                   (rx/filter (fn [{:keys [status queue]}]
                                (or (= status :error)
                                    (and (empty? queue)
                                         (or (nil? status) (= status :saved))))))
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
  "Refuses downgrades: a save in progress stays :saving, and a failed save
  stays :error until persistence is resumed."
  [from to]
  (cond
    (and (= to :pending) (= from :saving))         from
    (and (= from :error) (#{:pending :saving} to)) from
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
                    (dissoc :run-id :last-progress-at :stall-reported?))))))))

(defn- report-stalled-persistence
  [now]
  (ptk/reify ::report-stalled-persistence
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:persistence :stall-reported?] true))

    ptk/EffectEvent
    (effect [_ state _]
      (let [{:keys [queue index status run-id last-progress-at]} (:persistence state)
            commit-id (peek queue)
            commit    (get index commit-id)
            hint      "File saving has made no progress for more than five minutes"
            cause     (ex-info hint
                               {:type :persistence
                                :code :saving-stalled
                                :file-id (or (:file-id commit) (:current-file-id state))
                                :commit-id commit-id
                                :run-id run-id
                                :status status
                                :queued-commits (count queue)
                                :elapsed-ms (- now last-progress-at)
                                :can-edit (dm/get-in state [:permissions :can-edit])
                                :read-only? (dm/get-in state [:workspace-global :read-only?])
                                :preview-id (dm/get-in state [:workspace-global :preview-id])
                                :render-context-lost? (dm/get-in state [:render-state :lost])})]
        (errors/submit-report :event-name "handled-exception"
                              :hint hint
                              :report (errors/generate-report cause))))))

(defn- check-persistence
  []
  (ptk/reify ::check-persistence
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [status last-progress-at stall-reported?]} (:persistence state)
            now (inst-ms (ct/now))]
        (when (and (#{:pending :saving} status)
                   last-progress-at
                   (not stall-reported?)
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
                                       (dissoc :stall-reported?)))))))

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
          (when (and (not= :error (:status pstate))
                     (= run-id (:run-id pstate)))
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
                      (dissoc :run-id :last-progress-at :stall-reported?))))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event ::error cause)))

    ptk/EffectEvent
    (effect [_ _ _]
      ;; Report without invoking global handlers that may reload the file or
      ;; navigate away before the user can recover the retained changes.
      (errors/flash-persistence cause))))

(defn- commit-persisted
  [commit]
  (ptk/reify ::commit-persisted
    IDeref
    (-deref [_] commit)

    ptk/UpdateEvent
    (update [_ state]
      ;; Keep the acknowledgment even if the queue runner has stopped.
      (d/update-in-when state [:persistence :index (:id commit)]
                        assoc ::acknowledged? true))))

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
  [state commit-id request-id]
  (let [commit (dm/get-in state [:persistence :index commit-id])]
    (cond
      (= :error (dm/get-in state [:persistence :status]))  :halted
      (nil? commit)                                        :missing-commit
      (::acknowledged? commit)                             :acknowledged
      (contains? @active-requests (::request-id commit))   :in-flight
      (and (::request-id commit)
           (not= request-id (::request-id commit)))        :unknown-outcome
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
                     (rx/of (persistence-failed id cause)))))))

(defn- persist-commit
  [commit-id]
  (let [request-id (uuid/next)]
    (ptk/reify ::persist-commit
      ptk/UpdateEvent
      (update [_ state]
        (if (= :ready (attempt-state state commit-id request-id))
          ;; Record the attempt before starting I/O. An interrupted request
          ;; may have reached the server and must not be replayed blindly.
          (assoc-in state [:persistence :index commit-id ::request-id] request-id)
          state))

      ptk/WatchEvent
      (watch [_ state _]
        (let [commit (dm/get-in state [:persistence :index commit-id])
              fail   (fn [code hint]
                       (rx/of (persistence-failed commit-id
                                                  (ex-info hint {:type :persistence
                                                                 :code code
                                                                 :commit-id commit-id
                                                                 :file-id (:file-id commit)}))))]
          (case (attempt-state state commit-id request-id)
            :halted            (rx/empty)
            :missing-commit    (fail :missing-commit "A queued save has no change data")
            :acknowledged      (rx/of (commit-persisted commit))
            ;; The replacement runner listens for the original request's result.
            :in-flight         (rx/empty)
            ;; Even :network and :offline do not prove that the server
            ;; skipped the write. Keep the attempt stamp to prevent replay.
            :unknown-outcome   (fail :save-outcome-unknown "An interrupted save has an unknown outcome")
            :permission-denied (fail :save-permission-denied "Edit permission was lost before changes could be saved")
            :ready             (send-queued-commit request-id (:session-id state) commit)))))))


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
          (rx/of (update-status :saved)))))))

(defn- resume-persistence
  []
  (ptk/reify ::resume-persistence
    ptk/UpdateEvent
    (update [_ state]
      (update state :persistence
              (fn [pstate]
                (-> pstate
                    (dissoc :error)
                    (assoc :run-id (uuid/next) :status :saving)
                    (update :last-progress-at d/nilv (inst-ms (ct/now)))))))
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (run-persistence-task)))))

(defn- recover-persistence
  []
  (ptk/reify ::recover-persistence
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [queue index status error run-id]} (:persistence state)
            commit (get index (peek queue))]
        (cond
          (and (seq queue)
               (or (not= status :error)
                   (and (= :save-permission-denied (:code error))
                        (not (::request-id commit))
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
