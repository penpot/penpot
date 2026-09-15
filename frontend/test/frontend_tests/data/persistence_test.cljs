;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.persistence-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as dps]
   [app.main.data.render-wasm :as drw]
   [app.main.errors :as errors]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- local-commit
  [file-id]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :redo-changes [{:type :mod-page :id (uuid/next) :name "Edited"}]
                   :undo-changes []}))

(t/deftest queued-edits-save-during-temporary-read-only-mode
  (doseq [read-only-event [(drw/context-lost)
                           #(assoc-in % [:workspace-global :read-only?] true)
                           #(assoc % :workspace-global {:read-only? true
                                                        :preview-id (uuid/next)})]]
    (let [file-id  (uuid/next)
          response (rx/subject)
          errors   (atom [])
          store    (ptk/store {:state {:permissions {:can-edit true}
                                       :files {file-id {:id file-id :revn 0}}}
                               :on-error #(swap! errors conj %)})]
      (with-redefs [rp/cmd! (mock/stub (fn [_ _] (rx/take 1 response)))]
        (try
          (ptk/emit! store (dps/initialize-persistence)
                     (local-commit file-id)
                     read-only-event
                     ::dps/force-persist)
          (rx/push! response {:revn 1})
          (t/is (= :saved (get-in @store [:persistence :status])))
          (t/is (empty? (get-in @store [:persistence :queue])))

          (ptk/emit! store (drw/context-restored)
                     #(assoc-in % [:workspace-global :read-only?] false)
                     (local-commit file-id)
                     ::dps/force-persist)
          (rx/push! response {:revn 2})
          (t/is (= :saved (get-in @store [:persistence :status])))
          (t/is (empty? (get-in @store [:persistence :queue])))
          (t/is (empty? @errors))
          (finally
            (rx/dispose! store)
            (rx/end! response)))))))

(t/deftest historical-preview-cannot-create-local-commits
  (let [file-id (uuid/next)
        output  (atom [])
        state   {:current-file-id file-id
                 :permissions {:can-edit true}
                 :files {file-id {:id file-id :revn 0 :vern 0}}
                 :workspace-global {:read-only? true :preview-id (uuid/next)}}
        event   (dch/commit-changes {:redo-changes [] :undo-changes []})]
    (when-let [result (ptk/watch event state (rx/empty))]
      (->> result (rx/subs! #(swap! output conj %))))
    (t/is (empty? @output))))

(defn- with-watchdog
  [f]
  (let [clock    (atom 0)
        ticks    (rx/subject)
        response (rx/subject)
        reports  (atom [])
        causes   (atom [])
        render   errors/generate-report
        file-id  (uuid/next)
        store    (ptk/store {:state {:current-file-id file-id
                                     :permissions {:can-edit true}
                                     :files {file-id {:id file-id :revn 0}}}
                             :on-error #(t/is false (str %))})]
    (with-redefs [ct/now                 (mock/stub #(ct/inst @clock))
                  rx/interval            (mock/stub (fn [_] ticks))
                  rp/cmd!                (mock/stub (fn [_ _] (rx/take 1 response)))
                  st/state               store
                  errors/generate-report (fn [cause]
                                           (swap! causes conj cause)
                                           (render cause))
                  errors/submit-report   (fn [& params]
                                           (swap! reports conj (apply hash-map params)))]
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (f {:clock clock :ticks ticks :response response :causes causes
            :reports reports :store store :file-id file-id})
        (finally
          (rx/dispose! store)
          (rx/end! ticks)
          (rx/end! response))))))

(t/deftest stalled-request-is-reported-once-without-discarding-edits
  (with-watchdog
    (fn [{:keys [clock ticks reports causes store file-id]}]
      (ptk/emit! store (local-commit file-id) ::dps/force-persist)
      (reset! clock 300000)
      (rx/push! ticks :tick)
      (t/is (empty? @reports) "Five minutes must elapse before reporting")

      ;; More local edits must not reset the stalled request's clock.
      (reset! clock 300001)
      (ptk/emit! store (drw/context-lost)
                 (local-commit file-id) ::dps/force-persist)
      (rx/push! ticks :tick)
      (t/is (= 1 (count @reports)))
      (t/is (= "handled-exception" (:event-name (first @reports))))
      (let [data (ex-data (first @causes))]
        (t/is (= :saving-stalled (:code data)))
        (t/is (= file-id (:file-id data)))
        (t/is (true? (:render-context-lost? data))))

      (reset! clock 900000)
      (rx/push! ticks :tick)
      (t/is (= 1 (count @reports)) "Do not repeat a report for the same stall")
      (t/is (= :saving (get-in @store [:persistence :status])))
      (t/is (= 2 (count (get-in @store [:persistence :queue])))))))

(t/deftest successful-saves-reset-the-stall-clock-and-allow-a-new-report
  (with-watchdog
    (fn [{:keys [clock ticks response reports store file-id]}]
      (ptk/emit! store (local-commit file-id) ::dps/force-persist
                 (local-commit file-id) ::dps/force-persist)
      (reset! clock 290000)
      (rx/push! response {:revn 1})
      (reset! clock 300001)
      (rx/push! ticks :tick)
      (t/is (empty? @reports) "The queue is making progress")

      (reset! clock 590001)
      (rx/push! ticks :tick)
      (t/is (= 1 (count @reports)) "The second request has now stalled")

      (rx/push! response {:revn 2})
      (t/is (= :saved (get-in @store [:persistence :status])))
      (reset! clock 1000000)
      (rx/push! ticks :tick)
      (t/is (= 1 (count @reports)) "A saved file must not be reported")

      (ptk/emit! store (local-commit file-id) ::dps/force-persist)
      (reset! clock 1300001)
      (rx/push! ticks :tick)
      (t/is (= 2 (count @reports)) "A later stall gets its own report"))))

(t/deftest pending-edits-are-monitored-without-extending-the-deadline
  (with-watchdog
    (fn [{:keys [clock ticks reports store]}]
      (rx/push! ticks :tick)
      (t/is (empty? @reports) "An idle file must not be reported")
      (ptk/emit! store (#'dps/update-status :pending))
      (reset! clock 300001)
      (ptk/emit! store (#'dps/update-status :pending))
      (rx/push! ticks :tick)
      (t/is (= 1 (count @reports)))
      (ptk/emit! store (#'dps/update-status :error))
      (reset! clock 900000)
      (rx/push! ticks :tick)
      (t/is (= 1 (count @reports)) "Do not report an already failed save"))))

(t/deftest reinitializing-persistence-replaces-the-watchdog
  (let [active-timers (atom 0)
        ticks         (rx/subject)
        store         (ptk/store {:state {} :on-error #(t/is false (str %))})]
    (with-redefs [rx/interval (mock/stub
                               (fn [_]
                                 (rx/create
                                  (fn [subscriber]
                                    (swap! active-timers inc)
                                    (let [subscription (.subscribe ticks subscriber)]
                                      (fn []
                                        (rx/dispose! subscription)
                                        (swap! active-timers dec)))))))]
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (t/is (= 1 @active-timers))
        (ptk/emit! store (dps/initialize-persistence))
        (t/is (= 1 @active-timers))
        (finally
          (rx/dispose! store)
          (rx/end! ticks))))
    (t/is (zero? @active-timers))))

(defn- with-persistence
  [f]
  (let [file-id  (uuid/next)
        response (rx/subject)
        failures (atom [])
        requests (atom [])
        store    (ptk/store {:state {:current-file-id file-id
                                     :permissions {:can-edit true}
                                     :files {file-id {:id file-id :revn 0}}}
                             :on-error #(t/is false (str %))})]
    (with-redefs [rp/cmd! (mock/stub (fn [cmd params]
                                       (swap! requests conj [cmd params])
                                       (rx/take 1 response)))
                  errors/flash (fn [& {:keys [cause]}]
                                 (swap! failures conj cause))]
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (f {:file-id file-id :response response :failures failures
            :requests requests :store store})
        (finally
          (rx/dispose! store)
          (rx/end! response))))))

(t/deftest permission-loss-fails-without-discarding-queued-edits
  (with-persistence
    (fn [{:keys [file-id requests failures store]}]
      (ptk/emit! store (local-commit file-id)
                 #(assoc-in % [:permissions :can-edit] false)
                 ::dps/force-persist)
      (t/is (= :error (get-in @store [:persistence :status])))
      (t/is (= 1 (count (get-in @store [:persistence :queue]))))
      (t/is (empty? @requests))
      (t/is (= 1 (count @failures)))
      (ptk/emit! store (local-commit file-id) ::dps/force-persist
                 (#'dps/update-status :pending))
      (t/is (= :error (get-in @store [:persistence :status])))
      (t/is (= 2 (count (get-in @store [:persistence :queue])))))))

(t/deftest failed-request-retains-the-queue-and-is-not-retried-on-initialization
  (with-persistence
    (fn [{:keys [file-id response requests store]}]
      (ptk/emit! store (local-commit file-id) ::dps/force-persist
                 (local-commit file-id) ::dps/force-persist)
      (.error response (ex-info "Connection lost" {:type :network}))
      (ptk/emit! store (dps/initialize-persistence))
      (t/is (= :error (get-in @store [:persistence :status])))
      (t/is (= 2 (count (get-in @store [:persistence :queue]))))
      (t/is (= 1 (count @requests))))))

(t/deftest save-failures-use-a-translated-warning-except-for-authentication
  (doseq [cause-type [:network :offline :authentication]]
    (with-persistence
      (fn [{:keys [file-id response store]}]
        (let [notifications (atom [])]
          (with-redefs [errors/flash (fn [& params]
                                       (swap! notifications conj (apply hash-map params)))
                        i18n/tr (mock/stub #(str "translated:" %))]
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (.error response (ex-info "Raw transport details" {:type cause-type}))
            (t/is (= :error (get-in @store [:persistence :status])))
            (let [data (get-in @store [:persistence :error])]
              (ptk/handle-error (assoc data ::errors/instance (ex-info "Save failed" data))))
            (if (= cause-type :authentication)
              (t/is (empty? @notifications))
              (t/is (= ["translated:errors.save-failed"]
                       (mapv :hint @notifications))))))))))

(t/deftest missing-commit-is-an-error-instead-of-skipping-changes
  (with-persistence
    (fn [{:keys [requests store]}]
      (let [id (uuid/next)]
        (ptk/emit! store
                   #(assoc % :persistence {:queue (conj #queue [] id)
                                           :index {} :run-id (uuid/next)
                                           :status :saving})
                   (dps/initialize-persistence))
        (t/is (= :error (get-in @store [:persistence :status])))
        (t/is (= [id] (vec (get-in @store [:persistence :queue]))))
        (t/is (empty? @requests))))))

(t/deftest initialization-recovers-an-unsent-commit-with-a-dangling-run-id
  (with-persistence
    (fn [{:keys [file-id response requests store]}]
      (let [commit (assoc @(local-commit file-id) :changes [])
            id     (:id commit)]
        (ptk/emit! store
                   #(assoc % :persistence {:queue (conj #queue [] id)
                                           :index {id commit} :run-id (uuid/next)
                                           :status :saving})
                   (dps/initialize-persistence))
        (t/is (= 1 (count @requests)))
        (rx/push! response {:revn 1})
        (t/is (= :saved (get-in @store [:persistence :status])))
        (t/is (empty? (get-in @store [:persistence :queue])))))))

(t/deftest an-active-request-is-never-sent-twice
  (doseq [interrupt [[(dps/initialize-persistence)]
                     [(ptk/data-event ::dps/error)]]]
    (with-persistence
      (fn [{:keys [file-id response requests store]}]
        (apply ptk/emit! store (local-commit file-id) ::dps/force-persist interrupt)
        (ptk/emit! store (dps/initialize-persistence))
        (t/is (= 1 (count @requests)))
        (rx/push! response {:revn 1})
        (t/is (= :saved (get-in @store [:persistence :status])))
        (t/is (empty? (get-in @store [:persistence :queue])))))))

(t/deftest permission-restoration-resumes-only-unsent-edits
  (with-persistence
    (fn [{:keys [file-id response requests store]}]
      (ptk/emit! store (local-commit file-id) ::dps/force-persist
                 (local-commit file-id) ::dps/force-persist
                 #(assoc-in % [:permissions :can-edit] false))
      (rx/push! response {:revn 1})
      (t/is (= :error (get-in @store [:persistence :status])))
      (t/is (= 1 (count (get-in @store [:persistence :queue]))))
      (ptk/emit! store
                 #(assoc-in % [:permissions :can-edit] true)
                 (ptk/data-event :app.main.data.common/change-team-role))
      (t/is (= 2 (count @requests)))
      (rx/push! response {:revn 2})
      (t/is (= :saved (get-in @store [:persistence :status])))
      (t/is (empty? (get-in @store [:persistence :queue]))))))

(t/deftest recovery-keeps-an-acknowledgment-received-without-a-runner
  (with-persistence
    (fn [{:keys [file-id response requests store]}]
      (ptk/emit! store (local-commit file-id) ::dps/force-persist
                 (ptk/data-event ::dps/error))
      (rx/push! response {:revn 1})
      (t/is (= 1 (count (get-in @store [:persistence :queue]))))
      (ptk/emit! store (dps/initialize-persistence))
      (t/is (= 1 (count @requests)) "The acknowledged changes must not be sent again")
      (t/is (= :saved (get-in @store [:persistence :status])))
      (t/is (empty? (get-in @store [:persistence :queue]))))))

(t/deftest recovery-reports-an-unknown-request-outcome-without-replaying-it
  (with-persistence
    (fn [{:keys [file-id requests store]}]
      (let [commit (assoc @(local-commit file-id) ::dps/request-id (uuid/next))
            id     (:id commit)]
        (ptk/emit! store
                   #(assoc % :persistence {:queue (conj #queue [] id)
                                           :index {id commit} :status :saving})
                   (dps/initialize-persistence))
        (t/is (= :error (get-in @store [:persistence :status])))
        (t/is (= :save-outcome-unknown (get-in @store [:persistence :error :code])))
        (t/is (= [id] (vec (get-in @store [:persistence :queue]))))
        (t/is (empty? @requests))))))

(t/deftest initialization-flushes-buffered-edits-without-duplicating-them
  (with-persistence
    (fn [{:keys [file-id response requests store]}]
      (ptk/emit! store (local-commit file-id)
                 (dps/initialize-persistence)
                 ::dps/force-persist)
      (t/is (= 1 (count @requests)))
      (t/is (= 1 (count (get-in @store [:persistence :queue]))))
      (rx/push! response {:revn 1})
      (t/is (= :saved (get-in @store [:persistence :status]))))))

(t/deftest synchronous-save-results-do-not-leave-a-dangling-runner
  (with-persistence
    (fn [{:keys [file-id store]}]
      (with-redefs [rp/cmd! (mock/stub (fn [_ _] (rx/of {:revn 1})))]
        (ptk/emit! store (local-commit file-id) ::dps/force-persist)
        (t/is (= :saved (get-in @store [:persistence :status])))
        (t/is (empty? (get-in @store [:persistence :queue])))))))

(t/deftest empty-or-invalid-save-responses-preserve-the-queue-as-failed
  (doseq [result [(rx/empty) (rx/of nil) (rx/of {:revn -1})]]
    (with-persistence
      (fn [{:keys [file-id store]}]
        (with-redefs [rp/cmd! (mock/stub (fn [_ _] result))]
          (ptk/emit! store (local-commit file-id) ::dps/force-persist)
          (t/is (= :error (get-in @store [:persistence :status])))
          (t/is (= :invalid-save-response (get-in @store [:persistence :error :code])))
          (t/is (= 1 (count (get-in @store [:persistence :queue])))))))))
