;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.persistence-outbox-test
  "Tests for the durable outbox of the save queue
  (`app.main.data.persistence.outbox` and its use in
  `app.main.data.persistence`): queued commits are stored until
  acknowledged, abandoned ones are sent again when the file opens, and the
  ones that can no longer be sent safely are restored in a copy.

  Runs on an in-memory outbox backend and the runtime's Web Locks."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as dps]
   [app.main.data.persistence.outbox :as obx]
   [app.main.errors :as errors]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private reload-event ::reload-file)

(defn- stored-rows
  [backend]
  (vals @(.-rows* ^js backend)))

(defn- local-commit
  [file-id]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 5
                   :file-vern 0
                   :source :local
                   :created-at (ct/now)
                   :features #{}
                   :redo-changes [{:type :mod-page :id (uuid/next) :name "Edited"}]
                   :undo-changes []}))

(defn- stored-row
  "An outbox row of `file-id` written by `session-id`, `age-ms` ago."
  [{:keys [file-id profile-id session-id vern age-ms changes]
    :or {vern 0 age-ms 1000}}]
  (let [commit {:id (uuid/next)
                :file-id file-id
                :file-revn 3
                :file-vern vern
                :features #{}
                :origin :test
                :created-at (ct/now)
                :changes (or changes [{:type :mod-page :id (uuid/next) :name "Offline edit"}])}]
    {:id (:id commit)
     :profile-id profile-id
     :file-id file-id
     :session-id session-id
     :created-at (- (inst-ms (ct/now)) age-ms)
     :commit commit}))

(defn- with-outbox
  "Runs `f` with persistence initialized on a store that has a profile and
  a loaded file (revn 5, vern 0), an in-memory outbox and a transport
  answered by `respond` (`(fn [cmd params] observable)`). Every event that
  reaches the store is recorded by type."
  [f respond]
  (let [file-id    (uuid/next)
        profile-id (uuid/next)
        session-id (uuid/next)
        backend    (obx/memory-backend)
        requests   (atom [])
        events     (atom [])
        store      (ptk/store {:state {:current-file-id file-id
                                       :session-id session-id
                                       :profile {:id profile-id}
                                       :permissions {:can-edit true}
                                       :files {file-id {:id file-id :name "Design" :revn 5 :vern 0}}}
                               :on-error #(t/is false (str %))})]
    (rx/subs! #(swap! events conj (ptk/type %)) (ptk/input-stream store))
    (mock/with-mocks*
      {rp/cmd!      (mock/stub (fn [cmd params]
                                 (swap! requests conj [cmd params])
                                 (respond cmd params)))
       obx/backend  backend
       ;; The real reload initializes the whole workspace.
       dps/reload-file (mock/stub #(ptk/data-event ::reload-file))
       errors/flash (fn [& _] nil)}
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (await (f {:file-id file-id :profile-id profile-id :session-id session-id
                   :backend backend :requests requests :events events :store store}))
        (finally
          (rx/dispose! store))))))

(defn- open-file!
  [store]
  (ptk/emit! store (ptk/data-event :app.main.data.workspace/workspace-initialized)))

;; Scenario: an edit is queued and saved. Its row exists while the save is in
;; flight and is gone once the backend acknowledges it. Proves: the outbox
;; mirrors the queue.
(t/deftest ^:async queued-commit-is-stored-until-acknowledged
  (let [response (rx/subject)]
    (await
     (with-outbox
       (^:async fn [{:keys [file-id backend store]}]
         (ptk/emit! store (local-commit file-id) ::dps/force-persist)
         (await (async/wait-for #(= 1 (count (stored-rows backend))) "row stored"))
         (t/is (= file-id (:file-id (first (stored-rows backend)))))
         (rx/push! response {:revn 6})
         (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                      (empty? (stored-rows backend)))
                                "row deleted on ack"))
         (t/is (empty? (stored-rows backend))))
       (fn [_ _] (->> response (rx/take 1) (rx/observe-on :async)))))))

;; Scenario: a row left by a session that is gone is found when the file
;; opens. It is sent with its original commit id at the file's current revn,
;; deleted, and the file is reloaded. Proves: abandoned edits reach the
;; backend without a chance of applying twice.
(t/deftest ^:async abandoned-row-is-replayed-and-file-reloaded
  (await
   (with-outbox
     (^:async fn [{:keys [file-id profile-id backend requests events store]}]
       (let [row (stored-row {:file-id file-id :profile-id profile-id :session-id (uuid/next)})]
         (await (obx/put! row))
         (open-file! store)
         (await (async/wait-for #(some #{reload-event} @events) "file reloaded"))
         (let [[cmd params] (first @requests)]
           (t/is (= 1 (count @requests)))
           (t/is (= :update-file cmd))
           (t/is (= (:id row) (:commit-id params)) "original commit id")
           (t/is (= 5 (:revn params)) "current revn of the file")
           (t/is (= (get-in row [:commit :changes]) (:changes params))))
         (t/is (empty? (stored-rows backend)))
         ;; Loading the workspace hides notifications: the notice waits for
         ;; the reloaded workspace instead of being wiped by it.
         (t/is (nil? (get-in @store [:notification :content])))
         (open-file! store)
         (await (async/wait-for #(some? (get-in @store [:notification :content]))
                                "notice shown after the reload"))
         (t/is (= :info (get-in @store [:notification :level])))))
     (fn [_ _] (rx/of {:revn 6})))))

;; Scenario: a row whose session still holds its lock (another open tab).
;; Opening the file leaves it alone. Proves: a tab never sends edits another
;; running tab owns.
(t/deftest ^:async row-of-a-live-session-is-left-alone
  (let [owner   (uuid/next)
        release (atom nil)]
    (await
     (with-outbox
       (^:async fn [{:keys [file-id profile-id backend requests store]}]
         (js/navigator.locks.request (str "penpot-outbox-session:" owner)
                                     (fn [_] (js/Promise. (fn [resolve] (reset! release resolve)))))
         (await (async/wait-for #(some? @release) "owner lock held"))
         (await (obx/put! (stored-row {:file-id file-id :profile-id profile-id :session-id owner})))
         (open-file! store)
         (await (async/settle))
         (await (async/settle))
         (t/is (empty? @requests))
         (t/is (= 1 (count (stored-rows backend))))
         (@release))
       (fn [_ _] (rx/of {:revn 6}))))))

;; Scenario: rows that are too old, or made before the file was restored to
;; another version. Nothing is sent to the file; the restore offer shows.
;; Proves: unsafe rows never reach the original file.
(t/deftest ^:async unsafe-rows-are-offered-as-a-copy
  (await
   (with-outbox
     (^:async fn [{:keys [file-id profile-id backend requests store]}]
       (await (obx/put! (stored-row {:file-id file-id :profile-id profile-id :session-id (uuid/next)
                                     :vern 1})))
       (await (obx/put! (stored-row {:file-id file-id :profile-id profile-id :session-id (uuid/next)
                                     :age-ms (+ dps/outbox-replay-window-ms 1000)})))
       (open-file! store)
       (await (async/wait-for #(= :outbox-restore (get-in @store [:notification :tag])) "offer shown"))
       (t/is (empty? @requests))
       (t/is (= 2 (count (stored-rows backend)))))
     (fn [_ _] (rx/of {:revn 6})))))

;; Scenario: the user accepts the offer. The file is duplicated and the
;; stored changes are applied to the copy in one save, with references to
;; the original file pointing to the copy; the rows are deleted. Proves:
;; restore-as-a-copy keeps the edits and never touches the original.
(t/deftest ^:async restore-as-copy-applies-changes-to-a-duplicate
  (let [copy-id (uuid/next)
        page-id (uuid/next)
        shape-id (uuid/next)]
    (await
     (with-outbox
       (^:async fn [{:keys [file-id profile-id backend requests store]}]
         (await (obx/put! (stored-row {:file-id file-id :profile-id profile-id :session-id (uuid/next)
                                       :vern 1
                                       :changes [{:type :mod-obj :id shape-id :page-id page-id
                                                  :operations [{:type :set :attr :component-file
                                                                :val file-id}]}]})))
         (open-file! store)
         (await (async/wait-for #(= :outbox-restore (get-in @store [:notification :tag])) "offer shown"))
         ((get-in @store [:notification :accept :callback]))
         (await (async/wait-for #(empty? (stored-rows backend)) "rows deleted after restore"))
         (let [[[dcmd dparams] [ucmd uparams]] @requests]
           (t/is (= :duplicate-file dcmd))
           (t/is (= file-id (:file-id dparams)))
           (t/is (= :update-file ucmd))
           (t/is (= copy-id (:id uparams)) "saved to the copy")
           (t/is (= copy-id (get-in uparams [:changes 0 :operations 0 :val]))
                 "self references point to the copy"))
         (t/is (not-any? (fn [[cmd params]] (and (= :update-file cmd) (= file-id (:id params))))
                         @requests)
               "the original file is not saved"))
       (fn [cmd _]
         (case cmd
           :duplicate-file (rx/of {:id copy-id :revn 0 :vern 0 :features #{}})
           :update-file    (rx/of {:revn 1})))))))

;; Scenario: a replay hits a transport failure. The row stays stored and
;; nothing is reloaded. Proves: being offline at open loses nothing.
(t/deftest ^:async transient-replay-failure-keeps-the-row
  (await
   (with-outbox
     (^:async fn [{:keys [file-id profile-id backend requests events store]}]
       (await (obx/put! (stored-row {:file-id file-id :profile-id profile-id :session-id (uuid/next)})))
       (open-file! store)
       (await (async/wait-for #(= 1 (count @requests)) "replay attempted"))
       (await (async/settle))
       (t/is (= 1 (count (stored-rows backend))))
       (t/is (not-any? #{reload-event} @events)))
     (fn [_ _] (rx/throw (ex-info "offline" {:type :offline}))))))

(t/deftest classify-outbox-row-rules
  (let [now  (inst-ms (ct/now))
        file {:vern 2}
        row  (fn [vern age] {:commit {:file-vern vern} :created-at (- now age)})]
    (t/is (= :replay (dps/classify-outbox-row (row 2 1000) file now)))
    (t/is (= :restore (dps/classify-outbox-row (row 1 1000) file now)) "version restored")
    (t/is (= :restore (dps/classify-outbox-row (row 2 (inc dps/outbox-replay-window-ms)) file now))
          "older than the replay window")
    (t/is (= :replay (dps/classify-outbox-row (row nil 1000) {:vern nil} now))
          "a missing version is version 0 on both sides")
    (t/is (= :replay (dps/classify-outbox-row (row nil 1000) {:vern 0} now)))))
