;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-websocket-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.websocket :as ws]
   [app.msgbus :as mbus]
   [app.nitrate :as nitrate]
   [app.redis :as rds]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as files-update]
   [app.rpc.commands.teams :as teams]
   [app.rpc.notifications :as ntf]
   [app.util.blob :as blob]
   [app.util.websocket :as util-ws]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [promesa.exec.csp :as sp]))

(defn- with-clean-registry
  "Runs `f` against an empty connection registry, restoring the previous
  contents afterwards so tests cannot leak connections into each other."
  [f]
  (let [saved @ws/state]
    (reset! ws/state {:connections {} :by-profile {}})
    (try (f)
         (finally (reset! ws/state saved)))))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset with-clean-registry)

(defn make-wsp
  [profile-id state output-ch]
  {::util-ws/id (uuid/next)
   ::util-ws/state state
   ::util-ws/output-ch output-ch
   ::ws/profile-id profile-id
   ::ws/session-id (uuid/next)})

(t/deftest subscribe-file-permission-check
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile1)
                                     :project-id (:default-project-id profile1)})
        cfg      th/*system*
        state    (atom {})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))]

    (t/testing "rejects unauthorized user"
      (let [wsp (make-wsp (:id profile2) state output-ch)]
        (t/is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"not found"
               ((get-method ws/handle-message :subscribe-file)
                cfg wsp {:file-id (:id file)})))))

    (t/testing "permission check passes for authorized user"
      (t/is (nil? (files/check-read-permissions! cfg (:id profile1) (:id file)))))))

(t/deftest subscribe-team-permission-check
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile1)})
        cfg      th/*system*
        state    (atom {})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))]

    (t/testing "rejects unauthorized user"
      (let [wsp (make-wsp (:id profile2) state output-ch)]
        (t/is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"not found"
               ((get-method ws/handle-message :subscribe-team)
                cfg wsp {:team-id (:id team)})))))

    (t/testing "permission check passes for authorized user"
      (t/is (nil? (teams/check-read-permissions! cfg (:id profile1) (:id team)))))))

(defn- subscribed-topics
  "Runs :subscribe-team for `profile-id` on `team-id` with `nitrate-call`
  standing in for nitrate; returns the topics and the stored subscription."
  [profile-id team-id nitrate-call]
  (let [state     (atom {})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))
        wsp       (make-wsp profile-id state output-ch)
        calls     (atom [])]
    (with-redefs [nitrate/call nitrate-call
                  mbus/sub!    (fn [_ & {:keys [topics]}]
                                 (swap! calls conj topics))]
      ((get-method ws/handle-message :subscribe-team)
       th/*system* wsp {:team-id team-id}))
    (some-> @state ::ws/team-subscription :channel sp/close!)
    {:topics @calls
     :subscription (::ws/team-subscription @state)}))

(t/deftest subscribe-team-subscribes-to-team-organization
  (let [profile (th/create-profile* 1 {:is-active true})
        team-id (:id (th/create-team* 1 {:profile-id (:id profile)}))
        org-id  (uuid/next)]

    (t/testing "adds the organization topic when the team has one"
      (let [{:keys [topics subscription]}
            (subscribed-topics (:id profile) team-id
                               (fn [_ method params]
                                 (when (= :get-team-organization method)
                                   {:id (:team-id params)
                                    :organization {:id org-id}})))]
        (t/is (= [[team-id org-id]] topics))
        (t/is (= org-id (:organization-id subscription)))))

    (t/testing "subscribes only to the team when it has no organization"
      (let [{:keys [topics subscription]}
            (subscribed-topics (:id profile) team-id (constantly nil))]
        (t/is (= [[team-id]] topics))
        (t/is (nil? (:organization-id subscription)))))

    (t/testing "subscribes to the team when nitrate fails"
      (let [{:keys [topics]}
            (subscribed-topics (:id profile) team-id
                               (fn [& _] (throw (ex-info "nitrate down" {}))))]
        (t/is (= [[team-id]] topics))))))

(t/deftest pointer-update-validates-file-id
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        cfg      th/*system*
        file-id  (:id file)
        sub-ch   (sp/chan :buf (sp/dropping-buffer 64))
        state    (atom {::ws/file-subscription {:file-id file-id
                                                :channel sub-ch
                                                :topic file-id}})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))
        wsp      (make-wsp (:id profile) state output-ch)]

    (t/testing "skips publish when file-id does not match subscription"
      (let [wrong-msg {:type :pointer-update
                       :file-id (uuid/next)
                       :position {:x 10 :y 20}
                       :zoom 1.0}]
        (t/is (nil?
               ((get-method ws/handle-message :pointer-update)
                cfg wsp wrong-msg)))))

    (t/testing "does nothing when no file subscription exists"
      (let [empty-state (atom {})
            empty-wsp   (make-wsp (:id profile) empty-state output-ch)
            msg         {:type :pointer-update
                         :file-id file-id
                         :position {:x 10 :y 20}
                         :zoom 1.0}]
        (t/is (nil?
               ((get-method ws/handle-message :pointer-update)
                cfg empty-wsp msg)))))))

;; --- SUBSCRIPTION REVOCATION
;;
;; GHSA-m53j-2766-6jqw: a websocket subscription checks read permission
;; once, at subscribe time. Once the connection is subscribed to the
;; message-bus topic it keeps receiving live content for the rest of its
;; life, even after access is revoked.

(def ^:private revocation-timeout-ms
  "How long to wait for a message that should never arrive. Long enough
  to absorb the round trip through redis, short enough to keep the test
  fast."
  500)

(defn- drain-window!
  "Blocks for up to `ms` draining `ch`, and returns the first message
  whose `:type` is `type`, or nil when the window passes without one."
  [ch type ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [remain (- deadline (System/currentTimeMillis))]
        (when (pos? remain)
          (let [[msg _] (sp/alts! [ch (sp/timeout-chan remain)])]
            (if (= type (:type msg))
              msg
              (recur))))))))

(defn- poll-msg!
  "Drains `ch` for up to `ms`, returning the first message whose `:type`
  is `type`, or nil when none arrives.

  Draining rather than reading a single message matters for the negative
  assertions: a live subscription also carries presence traffic, so
  \"no `:file-change` arrived\" is the property under test, not \"the
  channel stayed empty\"."
  [ch type ms]
  (let [result (promise)]
    (sp/go
      (when-let [msg (drain-window! ch type ms)]
        (deliver result msg)))
    (deref result (inc ms) nil)))

(def ^:private delivery-attempts
  "How many times a positive delivery assertion publishes before giving
  up. The second attempt only happens when the subscription was not known
  to redis yet; more than that means the traffic is not flowing at all."
  3)

(defn- poll-published!
  "Publishes with `publish!` and returns the first message of `type` that
  reaches `ch`, publishing again when a window of `ms` passes without one.

  The subscription reaches redis through an asynchronous action, so a
  message published right after subscribing can be lost before redis knows
  the subscription, and a single publish makes the test bet on winning
  that race. Repeating it after a window that saw nothing removes the
  race without weakening the assertion: the test still proves that the
  client receives a message it published, and a publish only repeats when
  the window saw nothing."
  [ch publish! type ms]
  (let [result (promise)]
    (sp/go
      (loop [attempt 1]
        (when (<= attempt delivery-attempts)
          (publish!)
          (if-let [msg (drain-window! ch type ms)]
            (deliver result msg)
            (recur (inc attempt))))))
    (deref result (+ (* (inc delivery-attempts) ms) 100) nil)))

(defn- open-registered-connection
  "Registers a connection for `profile-id` in the real registry, so the
  revocation watcher can find it, and returns the pieces a test needs to
  drive it and inspect what it did."
  [profile-id]
  (let [id       (uuid/next)
        output   (sp/chan :buf (sp/dropping-buffer 64))
        ws-state (atom {})
        wsp      (make-wsp profile-id ws-state output)]
    (ws/register-connection id wsp)
    {:id id :wsp wsp :state ws-state :output output}))

(defn- open-subscription!
  "Opens a registered subscription for `profile-id` and returns the
  output channel that receives whatever it forwards.

  The connection goes through the real registry on purpose: these tests
  assert the end-to-end path, where a mutation announces the change and
  the watcher closes the subscription."
  [handler profile-id params]
  (let [{:keys [wsp output]} (open-registered-connection profile-id)]
    ((get-method ws/handle-message handler) th/*system* wsp params)
    output))

(defn- wait-until
  "Polls `pred` until it holds or `ms` elapses. Returns whether it held."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (pred)                                   true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 25) (recur))))))

(defn- subscribe
  [{:keys [wsp] :as conn} handler params]
  ((get-method ws/handle-message handler) th/*system* wsp params)
  conn)

(defn- subscribed-file [conn] (-> @(:state conn) ::ws/file-subscription :file-id))
(defn- subscribed-team [conn] (-> @(:state conn) ::ws/team-subscription :team-id))

(defn- publisher!
  "Returns a fn that publishes `message` on `topic` through the system
  msgbus, the same path as save notifications."
  []
  (let [msgbus (::mbus/msgbus th/*system*)]
    (fn [topic message]
      (mbus/pub! msgbus :topic topic :message message))))

(defn- save-params
  [profile file session-id revn]
  {::rpc/profile-id (:id profile)
   :id (:id file) :session-id session-id :revn revn :vern 0
   :features cfeat/supported-features})

(defn- set-shape-attr
  [page-id shape-id attr value]
  {:type :mod-obj :page-id page-id :id shape-id
   :operations [{:type :set :attr attr :val value}]})

(defn- get-broken-file-fixture
  [i]
  (let [profile (th/create-profile* i {:is-active true})
        file    (th/create-file* i {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})
        page-id (first (get-in file [:data :pages]))
        shape   (uuid/next)
        out     (th/command! (assoc (save-params profile file (uuid/next) 0)
                                    ::th/type :update-file
                                    :skip-validate true
                                    :changes [(th/add-rect-change page-id shape)
                                              (set-shape-attr page-id shape :frame-id (uuid/next))]))]
    (t/is (th/success? out))
    {:file file :profile profile :page-id page-id :shape shape :revn 1}))

(defn- opt-in-save
  [{:keys [profile file]} session-id revn changes]
  (th/command! (assoc (save-params profile file session-id revn)
                      ::th/type :update-file-v2
                      :repair-in-response true
                      :commits [{:commit-id (uuid/next) :changes changes}])))

(defn- await-topic
  [conn topic]
  (let [publish (publisher!)
        probe   (uuid/next)
        msg     (poll-published! (:output conn) #(publish topic {:type probe}) probe 500)]
    (t/is (= probe (:type msg)))))

(defn- close-save-subscriptions
  [{:keys [id wsp state output]}]
  (when-let [file-id (get-in @state [::ws/file-subscription :file-id])]
    (ws/close-file-subscription th/*system* wsp file-id))
  (when-let [team-id (get-in @state [::ws/team-subscription :team-id])]
    (ws/close-team-subscription th/*system* wsp team-id))
  (ws/unregister-connection id)
  (sp/close! output))

(t/deftest saves-mark-library-deltas-after-sharing-an-open-file
  (let [profile    (th/create-profile* 1 {:is-active true})
        profile-id (:id profile)
        team-id    (:default-team-id profile)
        file       (th/create-file* 1 {:profile-id profile-id :project-id (:default-project-id profile)})
        file-id    (:id file)
        page-id    (first (get-in file [:data :pages]))
        saver      (subscribe (open-registered-connection profile-id) :subscribe-file {:file-id file-id})
        peer       (subscribe (open-registered-connection profile-id) :subscribe-file {:file-id file-id})
        library    (subscribe (open-registered-connection profile-id) :subscribe-team {:team-id team-id})
        session-id (get-in saver [:wsp ::ws/session-id])]
    (try
      (await-topic saver file-id)
      (await-topic peer file-id)
      (await-topic library team-id)
      (t/is (false? (:is-shared file)))
      (t/is (true? (get-in (th/command! {::th/type :set-file-shared
                                         ::rpc/profile-id profile-id
                                         :id file-id
                                         :is-shared true})
                           [:result :is-shared])))
      (doseq [[revn method opt-in] [[0 :update-file false]
                                    [1 :update-file-v2 false]
                                    [2 :update-file-v2 true]]]
        (t/testing (str method ", repair-in-response: " opt-in)
          (let [rect     (th/add-rect-change page-id (uuid/next))
                changes  [{:type :mod-page :id page-id :name (str "Page " revn)} rect]
                params   (cond-> (assoc (save-params profile file session-id revn) ::th/type method)
                           (= :update-file method)
                           (assoc :changes changes)

                           (= :update-file-v2 method)
                           (assoc :commits [{:commit-id (uuid/next)
                                             :changes changes}])

                           opt-in
                           (assoc :repair-in-response true))
                out      (th/command! params)
                file-msg (poll-msg! (:output peer) :file-change 1000)
                team-msg (poll-msg! (:output library) :library-change 1000)]
            (t/is (th/success? out))
            (t/is (= (inc revn) (:revn file-msg) (:revn team-msg)))
            (t/is (= session-id (:session-id file-msg) (:session-id team-msg)))
            (t/is (true? (:library-change file-msg)))
            (t/is (= changes (:changes file-msg)))
            (t/is (= [rect] (:changes team-msg)))
            (t/is (not (contains? team-msg :library-change))))))
      (t/is (nil? (poll-msg! (:output saver) :file-change revocation-timeout-ms)))
      (finally
        (close-save-subscriptions saver)
        (close-save-subscriptions peer)
        (close-save-subscriptions library)))))

(t/deftest private-files-and-page-only-edits-do-not-publish-library-deltas
  (let [profile    (th/create-profile* 1 {:is-active true})
        profile-id (:id profile)
        team-id    (:default-team-id profile)
        file       (th/create-file* 1 {:profile-id profile-id :project-id (:default-project-id profile)})
        file-id    (:id file)
        page-id    (first (get-in file [:data :pages]))
        peer       (subscribe (open-registered-connection profile-id) :subscribe-file {:file-id file-id})
        library    (subscribe (open-registered-connection profile-id) :subscribe-team {:team-id team-id})
        fixture    {:profile profile :file file}
        session-id (uuid/next)]
    (try
      (await-topic peer file-id)
      (await-topic library team-id)
      (t/testing "A library edit in a private file"
        (let [changes [(th/add-rect-change page-id (uuid/next))]
              out     (opt-in-save fixture session-id 0 changes)
              msg     (poll-msg! (:output peer) :file-change 1000)]
          (t/is (th/success? out))
          (t/is (= changes (:changes msg)))
          (t/is (not (contains? msg :library-change)))
          (t/is (nil? (poll-msg! (:output library) :library-change revocation-timeout-ms)))))
      (t/is (true? (get-in (th/command! {::th/type :set-file-shared
                                         ::rpc/profile-id profile-id
                                         :id file-id
                                         :is-shared true})
                           [:result :is-shared])))
      (t/testing "A page-only edit in a shared file"
        (let [changes [{:type :mod-page :id page-id :name "Renamed page"}]
              out     (opt-in-save fixture session-id 1 changes)
              msg     (poll-msg! (:output peer) :file-change 1000)]
          (t/is (th/success? out))
          (t/is (= changes (:changes msg)))
          (t/is (not (contains? msg :library-change)))
          (t/is (nil? (poll-msg! (:output library) :library-change revocation-timeout-ms)))))
      (finally
        (close-save-subscriptions peer)
        (close-save-subscriptions library)))))

(t/deftest rolled-back-shared-save-does-not-publish-to-file-or-team
  (let [profile    (th/create-profile* 1 {:is-active true})
        profile-id (:id profile)
        team-id    (:default-team-id profile)
        file       (th/create-file* 1 {:profile-id profile-id
                                       :project-id (:default-project-id profile)
                                       :is-shared true})
        file-id    (:id file)
        page-id    (first (get-in file [:data :pages]))
        peer       (subscribe (open-registered-connection profile-id) :subscribe-file {:file-id file-id})
        library    (subscribe (open-registered-connection profile-id) :subscribe-team {:team-id team-id})
        after      db/after-commit
        fault      (ex-info "Rollback after save notification registration" {})]
    (try
      (await-topic peer file-id)
      (await-topic library team-id)
      (let [out (with-redefs [db/after-commit (fn [callback]
                                                (after callback)
                                                (throw fault))]
                  (opt-in-save {:profile profile :file file} (uuid/next) 0
                               [(th/add-rect-change page-id (uuid/next))]))]
        (t/is (identical? fault (:error out)))
        (t/is (= 0 (:revn (th/db-get :file {:id file-id}))))
        (t/is (nil? (poll-msg! (:output peer) :file-change revocation-timeout-ms)))
        (t/is (nil? (poll-msg! (:output library) :library-change revocation-timeout-ms))))
      (finally
        (close-save-subscriptions peer)
        (close-save-subscriptions library)))))

(t/deftest opt-in-save-sends-one-repair-event-to-file-and-library-peers
  (with-redefs [cf/flags (conj cf/flags :file-validation)]
    (let [{:keys [profile file page-id shape revn] :as fixture} (get-broken-file-fixture 1)
          profile-id (:id profile)
          file-id    (:id file)
          team-id    (:default-team-id profile)
          params     {:file-id file-id}
          saver      (subscribe (open-registered-connection profile-id) :subscribe-file params)
          peer       (subscribe (open-registered-connection profile-id) :subscribe-file params)
          library    (subscribe (open-registered-connection profile-id) :subscribe-team {:team-id team-id})]
      (try
        (await-topic saver file-id)
        (await-topic peer file-id)
        (await-topic library team-id)
        (let [session-id (get-in saver [:wsp ::ws/session-id])
              rename     (set-shape-attr page-id shape :name "repaired edit")
              out        (opt-in-save fixture session-id revn
                                      [{:type :mod-page :id page-id :name "Page change"} rename])
              result     (:result out)
              file-msg   (poll-msg! (:output peer) :file-change 1000)
              lib-msg    (poll-msg! (:output library) :library-change 1000)]
          (t/is (th/success? out))
          (t/is (= (+ 2 revn) (:saved-revn result) (:revn file-msg) (:revn lib-msg)))
          (t/is (true? (:library-change file-msg)))
          (t/is (= (mapv :id (:written result)) (:revision-ids file-msg)))
          (t/is (= (into [{:type :mod-page :id page-id :name "Page change"} rename]
                         (get-in result [:repair :changes]))
                   (:changes file-msg)))
          (t/is (= (into [rename] (get-in result [:repair :changes])) (:changes lib-msg)))
          (doseq [msg [file-msg lib-msg]]
            (t/is (= {:session-id session-id :repair-base-revn revn :vern 0 :repaired true}
                     (select-keys msg [:session-id :repair-base-revn :vern :repaired]))))
          (t/is (nil? (poll-msg! (:output saver) :file-change revocation-timeout-ms)))
          (t/is (nil? (poll-msg! (:output peer) :file-change revocation-timeout-ms)))
          (t/is (nil? (poll-msg! (:output library) :library-change revocation-timeout-ms))))
        (finally
          (close-save-subscriptions saver)
          (close-save-subscriptions peer)
          (close-save-subscriptions library))))))

(t/deftest legacy-v1-and-v2-savers-still-receive-the-repair
  (with-redefs [cf/flags (conj cf/flags :file-validation)]
    (doseq [[i type] [[1 :update-file] [2 :update-file-v2]]]
      (let [{:keys [profile file page-id shape revn]} (get-broken-file-fixture i)
            team-id (:default-team-id profile)
            saver   (subscribe (open-registered-connection (:id profile)) :subscribe-file {:file-id (:id file)})
            library (subscribe (open-registered-connection (:id profile)) :subscribe-team {:team-id team-id})]
        (try
          (await-topic saver (:id file))
          (await-topic library team-id)
          (let [changes [(set-shape-attr page-id shape :name "legacy edit")]
                params  (assoc (save-params profile file (get-in saver [:wsp ::ws/session-id]) revn)
                               ::th/type type)
                out     (th/command! (merge params
                                            (if (= :update-file type)
                                              {:changes changes}
                                              {:commits [{:commit-id (uuid/next)
                                                          :changes changes}]})))
                msg         (poll-msg! (:output saver) :file-change 1000)
                library-fix (poll-msg! (:output library) :library-change 1000)]
            (t/is (th/success? out))
            (if (= :update-file type)
              (t/is (= #{:revn :lagged} (set (keys (:result out)))))
              (t/is (= [[:repair (inc revn)] [:edit (+ revn 2)]]
                       (mapv (juxt :kind :revn) (get-in out [:result :written])))))
            (t/is (= files-update/repair-session-id (:session-id msg)))
            (t/is (= (inc revn) (:revn msg)))
            (t/is (true? (:repaired msg)))
            (t/is (= #{:type :file-id :profile-id :session-id :revn :vern :changes :repaired :library-change :topic}
                     (set (keys msg))))
            (t/is (true? (:library-change msg)))
            (t/is (= (inc revn) (:revn library-fix)))
            (t/is (= (:session-id params) (:session-id library-fix)))
            (t/is (= #{:type :file-id :profile-id :session-id :revn :modified-at :changes :topic}
                     (set (keys library-fix))))
            (t/is (nil? (poll-msg! (:output saver) :file-change revocation-timeout-ms))))
          (finally
            (close-save-subscriptions saver)
            (close-save-subscriptions library)))))))

(t/deftest opt-in-fallback-publishes-repair-then-the-persisted-media-redo
  (with-redefs [cf/flags (conj cf/flags :file-validation)]
    (let [{:keys [profile file page-id shape revn] :as fixture} (get-broken-file-fixture 1)
          profile-id (:id profile)
          file-id    (:id file)
          team-id    (:default-team-id profile)
          source     (th/create-file* 2 {:profile-id profile-id :project-id (:default-project-id profile)})
          storage-id (uuid/next)
          _          (th/db-insert! :storage-object {:id storage-id :size 100 :backend "test"})
          image      (th/create-file-media-object* {:file-id (:id source) :media-id storage-id :mtype "image/jpeg"})
          fills      [{:fill-opacity 1 :fill-image (select-keys image [:id :width :height :mtype])}]
          broken     (bfc/get-file th/*system* file-id :realize? true)
          saver      (subscribe (open-registered-connection profile-id) :subscribe-file {:file-id file-id})
          peer       (subscribe (open-registered-connection profile-id) :subscribe-file {:file-id file-id})
          library    (subscribe (open-registered-connection profile-id) :subscribe-team {:team-id team-id})]
      (try
        (await-topic saver file-id)
        (await-topic peer file-id)
        (await-topic library team-id)
        (let [session-id (get-in saver [:wsp ::ws/session-id])
              out        (opt-in-save fixture session-id revn [(set-shape-attr page-id shape :fills fills)])
              result     (:result out)
              [r u]      (:written result)
              saver-fix  (poll-msg! (:output saver) :file-change 1000)
              peer-fix   (poll-msg! (:output peer) :file-change 1000)
              peer-edit  (poll-msg! (:output peer) :file-change 1000)
              lib-fix    (poll-msg! (:output library) :library-change 1000)
              lib-edit   (poll-msg! (:output library) :library-change 1000)
              stored     (bfc/get-file th/*system* file-id :realize? true)
              get-redo   (fn [{:keys [id]}]
                           (let [row (th/db-get :file-change {:file-id file-id :id id}
                                                {::db/remove-deleted false})]
                             (blob/decode (:changes row))))
              linked-id  (get-in stored [:data :pages-index page-id :objects shape :fills 0 :fill-image :id])]
          (t/is (th/success? out))
          (t/is (= {:revn revn :vern 0 :saved-revn (+ revn 2) :media-relinked true :recovery {:mode :none}}
                   (dissoc result :written)))
          (t/is (uuid? linked-id))
          (t/is (not= (:id image) linked-id))
          (t/is (= saver-fix peer-fix))
          (t/is (true? (:library-change peer-fix)))
          (t/is (true? (:library-change peer-edit)))
          (t/is (= [(:id r)] (:revision-ids peer-fix)))
          (t/is (= [(:id u)] (:revision-ids peer-edit)))
          (t/is (= {:session-id files-update/repair-session-id :revn (inc revn) :vern 0 :repaired true}
                   (select-keys peer-fix [:session-id :revn :vern :repaired :repair-base-revn])))
          (t/is (= {:session-id session-id :revn (inc revn) :vern 0 :repaired true}
                   (select-keys lib-fix [:session-id :revn :vern :repaired :repair-base-revn :revision-ids])))
          (t/is (= {:session-id session-id :revn (+ revn 2) :vern 0}
                   (select-keys lib-edit [:session-id :revn :vern :repaired :repair-base-revn :revision-ids])))
          (t/is (= (get-redo r) (:changes peer-fix) (:changes lib-fix)))
          (t/is (= (get-redo u) (:changes peer-edit) (:changes lib-edit)))
          (t/is (= (:data stored) (reduce cpc/process-changes (:data broken) (map :changes [peer-fix peer-edit]))))
          (t/is (nil? (poll-msg! (:output saver) :file-change revocation-timeout-ms))))
        (finally
          (close-save-subscriptions saver)
          (close-save-subscriptions peer)
          (close-save-subscriptions library))))))

(t/deftest later-commit-can-publish-before-an-aggregate-and-sort-before-its-rows
  (with-redefs [cf/flags (conj cf/flags :file-validation)]
    (let [{:keys [profile file page-id shape revn] :as fixture} (get-broken-file-fixture 1)
          peer      (subscribe (open-registered-connection (:id profile)) :subscribe-file {:file-id (:id file)})
          timestamp (ct/now)
          callback  (atom nil)
          after     db/after-commit]
      (try
        (await-topic peer (:id file))
        (let [first-save  (with-redefs [db/after-commit (fn [f] (after #(reset! callback f)))
                                        ct/now          (constantly timestamp)]
                            (opt-in-save fixture (uuid/next) revn [(set-shape-attr page-id shape :name "first")]))
              second-save (with-redefs [ct/now (constantly (ct/minus timestamp (ct/duration {:millis 1})))]
                            (opt-in-save fixture (uuid/next) revn [(set-shape-attr page-id shape :name "later")]))
              ordinary    (poll-msg! (:output peer) :file-change 1000)]
          (t/is (th/success? first-save))
          (t/is (th/success? second-save))
          ;; The later edit has the earliest timestamp, but its predecessor
          ;; proof and the rows of its own write follow revision order.
          (t/is (= [(inc revn) (+ revn 2)]
                   (mapv :revn (get-in second-save [:result :recovery :required]))))
          (t/is (= [(+ revn 3)] (mapv :revn (get-in second-save [:result :written]))))
          (t/is (= (+ revn 3) (get-in second-save [:result :saved-revn]) (:revn ordinary)))
          (t/is (= [(get-in second-save [:result :written 0 :id])] (:revision-ids ordinary)))
          (@callback)
          (let [aggregate (poll-msg! (:output peer) :file-change 1000)]
            (t/is (= (+ revn 2) (:revn aggregate)))
            (t/is (= revn (:repair-base-revn aggregate)))
            (t/is (= (mapv :id (get-in first-save [:result :written])) (:revision-ids aggregate)))
            (t/is (= (:revision-ids aggregate)
                     (mapv :id (get-in second-save [:result :recovery :required]))))))
        (finally (close-save-subscriptions peer))))))

;; --- CONNECTION REGISTRY

(t/deftest registry-indexes-connections-by-profile
  (let [profile-a (uuid/next)
        profile-b (uuid/next)
        conn-a    (uuid/next)
        conn-b    (uuid/next)
        conn-c    (uuid/next)]

    (t/testing "a profile with no connections has an empty index"
      (t/is (= #{} (ws/connections-for-profile profile-a))))

    (ws/register-connection conn-a {::ws/profile-id profile-a})
    (ws/register-connection conn-b {::ws/profile-id profile-a})
    (ws/register-connection conn-c {::ws/profile-id profile-b})

    (t/testing "connections are indexed under their profile"
      (t/is (= #{conn-a conn-b} (ws/connections-for-profile profile-a)))
      (t/is (= #{conn-c} (ws/connections-for-profile profile-b))))

    (t/testing "connections are retrievable by id"
      (t/is (= profile-a (::ws/profile-id (ws/get-connection conn-a))))
      (t/is (nil? (ws/get-connection (uuid/next)))))

    (t/testing "unregistering removes it from both the map and the index"
      (ws/unregister-connection conn-b)
      (t/is (= #{conn-a} (ws/connections-for-profile profile-a)))
      (t/is (nil? (ws/get-connection conn-b))))

    (t/testing "a profile whose last connection left is dropped from the index"
      (ws/unregister-connection conn-a)
      (t/is (= #{} (ws/connections-for-profile profile-a)))
      (t/is (not (contains? (:by-profile @ws/state) profile-a)))
      (t/is (= #{conn-c} (ws/connections-for-profile profile-b))))

    (t/testing "unregistering an unknown connection is a no-op"
      (ws/unregister-connection (uuid/next))
      (t/is (= #{conn-c} (ws/connections-for-profile profile-b)))
      (t/is (= conn-c (-> (:connections @ws/state) keys first))))))

(t/deftest close-file-subscription-drops-only-the-matching-file
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        other    (th/create-file* 2 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        output   (sp/chan :buf (sp/dropping-buffer 64))
        relay    (sp/chan :buf (sp/dropping-buffer 64))
        ws-state (atom {::ws/file-subscription
                        {:file-id (:id file)
                         :channel relay
                         :topic (:id file)}})
        wsp      (assoc (make-wsp (:id profile) ws-state output)
                        ::ws/state ws-state)]

    (t/testing "closing a file the connection is not subscribed to does nothing"
      (with-redefs [mbus/pub!  (fn [& _] (throw ::unexpected-publish))
                    mbus/purge! (fn [& _] (throw ::unexpected-purge))]
        (t/is (nil? (ws/close-file-subscription th/*system* wsp (:id other))))
        (t/is (some? (::ws/file-subscription @ws-state)))))

    (t/testing "closing the subscribed file purges the bus"
      (let [purged (atom [])]
        (with-redefs [mbus/purge! (fn [_ chans] (swap! purged conj chans))]
          (ws/close-file-subscription th/*system* wsp (:id file))
          (t/is (= 1 (count @purged))))))

    (t/testing "the relay channel is closed so the go-loop stops"
      (t/is (nil? (sp/poll! relay)))
      (t/is (true? (sp/closed? relay))))

    (t/testing "the subscription is forgotten, so closing again is a no-op"
      (t/is (nil? (::ws/file-subscription @ws-state)))
      (with-redefs [mbus/pub! (fn [& _] (throw ::unexpected-publish))]
        (t/is (nil? (ws/close-file-subscription th/*system* wsp (:id file))))))))

(t/deftest revoked-member-stops-receiving-file-changes
  (let [owner   (th/create-profile* 1 {:is-active true})
        editor  (th/create-profile* 2 {:is-active true})
        team    (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})

    (let [project (th/create-project* 1 {:profile-id (:id editor)
                                         :team-id (:id team)})
          file    (th/create-file* 1 {:profile-id (:id editor)
                                      :project-id (:id project)})
          file-id (:id file)
          publish (publisher!)
          change  {:type :file-change
                   :file-id file-id
                   :revn 1
                   :changes [{:type :add :id (uuid/next)}]}]

      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-file {:file-id file-id}))
            out (:output conn)]

        (t/testing "member receives file changes before revocation"
          (t/is (some? (poll-published! out #(publish file-id change)
                                        :file-change revocation-timeout-ms))))

        (t/testing "owner removes the member from the team"
          (let [result (th/command! {::th/type :delete-team-member
                                     ::rpc/profile-id (:id owner)
                                     :team-id (:id team)
                                     :member-id (:id editor)})]
            (t/is (th/success? result))))

        (t/testing "the mutation alone drops the subscription"
          (t/is (wait-until #(nil? (subscribed-file conn)) 3000)))

        (t/testing "revoked member stops receiving file changes"
          (publish file-id (assoc change :revn 2))
          (t/is (nil? (poll-msg! out :file-change
                                 revocation-timeout-ms))))))))
(t/deftest revoked-member-stops-receiving-library-changes
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})

    (let [team-id (:id team)
          publish (publisher!)
          change  {:type :library-change
                   :team-id team-id
                   :file-id (uuid/next)
                   :revn 1
                   :changes [{:type :add :id (uuid/next)}]}]

      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-team {:team-id team-id}))
            out (:output conn)]

        (t/testing "member receives library changes before revocation"
          (t/is (some? (poll-published! out #(publish team-id change)
                                        :library-change revocation-timeout-ms))))

        (t/testing "member leaves the team"
          (let [result (th/command! {::th/type :leave-team
                                     ::rpc/profile-id (:id editor)
                                     :id team-id})]
            (t/is (th/success? result))))

        (t/testing "leaving drops the team subscription"
          (t/is (wait-until #(nil? (subscribed-team conn)) 3000)))

        (t/testing "revoked member stops receiving library changes"
          (publish team-id (assoc change :revn 2))
          (t/is (nil? (poll-msg! out :library-change
                                 revocation-timeout-ms))))))))
(t/deftest role-downgrade-keeps-revocation-irrelevant
  "A role change that does not remove read access must not be treated as
  a revocation: `:viewer` can still read, so the subscription stays."
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})

    (let [project (th/create-project* 1 {:profile-id (:id editor)
                                         :team-id (:id team)})
          file    (th/create-file* 1 {:profile-id (:id editor)
                                      :project-id (:id project)})
          file-id (:id file)
          publish (publisher!)
          change  {:type :file-change :file-id file-id :revn 1 :changes []}
          out     (open-subscription! :subscribe-file (:id editor)
                                      {:file-id file-id})]

      (t/testing "editor is downgraded to viewer"
        (let [result (th/command! {::th/type :update-team-member-role
                                   ::rpc/profile-id (:id owner)
                                   :team-id (:id team)
                                   :member-id (:id editor)
                                   :role :viewer})]
          (t/is (th/success? result))))

      (t/testing "viewer keeps receiving file changes"
        (t/is (some? (poll-published! out #(publish file-id change)
                                      :file-change revocation-timeout-ms)))))))

;; --- REVOCATION WATCHER
;;
;; The registry is local to a backend instance while the message bus is
;; shared, so a revocation travels over the bus and each instance
;; re-verifies the connections it owns.

(defn- editor-in-team!
  "Builds an owner, an editor with read access to `team`, and a project
  and file inside it."
  [owner-i editor-i]
  (let [owner  (th/create-profile* owner-i {:is-active true})
        editor (th/create-profile* editor-i {:is-active true})
        team   (th/create-team* owner-i {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})
    (let [project (th/create-project* owner-i {:profile-id (:id editor)
                                               :team-id (:id team)})
          file    (th/create-file* owner-i {:profile-id (:id editor)
                                            :project-id (:id project)})]
      {:owner owner :editor editor :team team :file file})))

(defn- delete-team-membership!
  "Takes `profile-id` out of `team-id` the way an external change would:
  a direct row delete. Fires no command and publishes no announcement,
  so no `db/after-commit` hook can race the test."
  [team-id profile-id]
  (db/exec! th/*system*
            ["delete from team_profile_rel where team_id = ? and profile_id = ?"
             team-id profile-id]))

;; --- FILE SUBSCRIPTION CONFIRMATION

(defn- with-held-redis-subscribe
  "Pauses the first Redis SUBSCRIBE for `file-id`, leaving the real bus,
  Redis, and every other topic untouched."
  [file-id f]
  (let [subscribe rds/subscribe
        entered   (promise)
        release   (promise)]
    (with-redefs [rds/subscribe
                  (fn [conn [topic :as topics]]
                    (when (and (str/ends-with? topic (str "." file-id))
                               (deliver entered true))
                      (when-not (= true (deref release 5000 nil))
                        (throw (ex-info "Timed out waiting to release Redis SUBSCRIBE" {}))))
                    (subscribe conn topics))]
      (try
        (f entered release)
        (finally
          (deliver release true))))))

(t/deftest file-subscription-ready-follows-redis-confirmation
  (let [profile         (th/create-profile* 1 {:is-active true})
        profile-id      (:id profile)
        file            (th/create-file* 1 {:profile-id profile-id :project-id (:default-project-id profile)})
        file-id         (:id file)
        page-id         (first (get-in file [:data :pages]))
        fixture         {:file file :profile profile}
        conn            (open-registered-connection profile-id)
        output          (:output conn)
        subscription-id (uuid/next)
        remote-session  (uuid/next)]
    (try
      (with-held-redis-subscribe
        file-id
        (fn [entered release]
          (let [pending (future
                          (subscribe conn :subscribe-file {:file-id file-id
                                                           :subscription-id subscription-id}))]
            (t/is (true? (deref entered 2000 nil)))
            (t/is (= conn (deref pending 500 nil)) "The websocket handler does not wait for Redis")
            (t/is (nil? (poll-msg! output :file-subscription-ready revocation-timeout-ms)))
            (t/is (th/success? (opt-in-save fixture (get-in conn [:wsp ::ws/session-id]) 0
                                            [{:type :mod-page :id page-id :name "Before Redis"}])))
            (deliver release true)
            (t/is (= {:type :file-subscription-ready :topic file-id :subscription-id subscription-id}
                     (poll-msg! output :file-subscription-ready 2000)))
            (let [stored (bfc/get-file th/*system* file-id :realize? true)]
              (t/is (= 1 (:revn stored)))
              (t/is (= "Before Redis" (get-in stored [:data :pages-index page-id :name]))))
            (let [changes [{:type :mod-page :id page-id :name "After ready"}]]
              (t/is (th/success? (opt-in-save fixture remote-session 1 changes)))
              (t/is (= {:topic file-id :revn 2 :changes changes}
                       (select-keys (poll-msg! output :file-change 2000) [:topic :revn :changes])))))))
      (finally
        (close-save-subscriptions conn)))))

(t/deftest failed-redis-subscription-is-not-ready-and-a-new-attempt-can-recover
  (let [profile (th/create-profile* 1 {:is-active true})
        file-id (:id (th/create-file* 1 {:profile-id (:id profile) :project-id (:default-project-id profile)}))
        conn    (open-registered-connection (:id profile))
        peer    (open-registered-connection (:id profile))
        failed  (promise)
        real    rds/subscribe]
    (try
      (with-redefs [rds/subscribe (fn [conn [topic :as topics]]
                                    (if (and (str/ends-with? topic (str "." file-id))
                                             (deliver failed true))
                                      (throw (ex-info "Redis SUBSCRIBE failed" {}))
                                      (real conn topics)))]
        (subscribe conn :subscribe-file {:file-id file-id :subscription-id (uuid/next)})
        (t/is (true? (deref failed 2000 nil)))
        (t/is (nil? (poll-msg! (:output conn) :file-subscription-ready revocation-timeout-ms)))
        (let [new-id (uuid/next)]
          (subscribe peer :subscribe-file {:file-id file-id :subscription-id new-id})
          (t/is (= {:type :file-subscription-ready :topic file-id :subscription-id new-id}
                   (poll-msg! (:output peer) :file-subscription-ready 2000)))
          ((publisher!) file-id {:type :file-change :revn 1})
          (t/is (= 1 (:revn (poll-msg! (:output peer) :file-change 2000))))
          (t/is (nil? (poll-msg! (:output conn) :file-subscription-ready revocation-timeout-ms)))))
      (finally
        (close-save-subscriptions conn)
        (close-save-subscriptions peer)))))

(t/deftest replacing-a-pending-subscription-to-the-same-file-only-confirms-the-new-request
  (let [profile  (th/create-profile* 1 {:is-active true})
        file-id  (:id (th/create-file* 1 {:profile-id (:id profile) :project-id (:default-project-id profile)}))
        conn     (open-registered-connection (:id profile))
        entered  (promise)
        release  (promise)
        new-id   (uuid/next)
        readable files/has-read-permissions?]
    (try
      (with-redefs [files/has-read-permissions?
                    (fn [cfg profile-id candidate-id]
                      (let [allowed (readable cfg profile-id candidate-id)]
                        (when (and (= file-id candidate-id) (deliver entered true))
                          (deref release 5000 nil))
                        allowed))]
        (subscribe conn :subscribe-file {:file-id file-id :subscription-id (uuid/next)})
        (t/is (true? (deref entered 2000 nil)) "The older request is waiting after the Redis ack")
        (subscribe conn :subscribe-file {:file-id file-id :subscription-id new-id})
        (t/is (= {:type :file-subscription-ready :topic file-id :subscription-id new-id}
                 (poll-msg! (:output conn) :file-subscription-ready 2000)))
        (deliver release true)
        (t/is (nil? (poll-msg! (:output conn) :file-subscription-ready revocation-timeout-ms))))
      (finally
        (deliver release true)
        (close-save-subscriptions conn)))))

(t/deftest file-subscription-ready-requires-fresh-read-permission
  (let [{:keys [editor team file]} (editor-in-team! 1 2)
        profile-id (:id editor)
        file-id    (:id file)
        conn       (open-registered-connection profile-id)]
    (try
      (with-held-redis-subscribe
        file-id
        (fn [entered release]
          (subscribe conn :subscribe-file {:file-id file-id :subscription-id (uuid/next)})
          (t/is (true? (deref entered 2000 nil)))
          (delete-team-membership! (:id team) profile-id)
          (deliver release true)
          (t/is (wait-until #(nil? (subscribed-file conn)) 2000))
          (t/is (nil? (poll-msg! (:output conn) :file-subscription-ready revocation-timeout-ms)))))
      (finally
        (close-save-subscriptions conn)))))

(t/deftest a-permission-lookup-error-does-not-confirm-a-file-subscription
  (let [profile (th/create-profile* 1 {:is-active true})
        file-id (:id (th/create-file* 1 {:profile-id (:id profile) :project-id (:default-project-id profile)}))
        conn    (open-registered-connection (:id profile))]
    (try
      (with-held-redis-subscribe
        file-id
        (fn [entered release]
          (subscribe conn :subscribe-file {:file-id file-id :subscription-id (uuid/next)})
          (t/is (true? (deref entered 2000 nil)))
          (with-redefs [files/has-read-permissions? (fn [& _]
                                                      (throw (ex-info "Read permission lookup failed" {})))]
            (deliver release true)
            (t/is (wait-until #(nil? (subscribed-file conn)) 2000))
            (t/is (nil? (poll-msg! (:output conn) :file-subscription-ready revocation-timeout-ms))))))
      (finally
        (close-save-subscriptions conn)))))

(t/deftest closing-a-socket-while-redis-is-pending-does-not-announce-ready
  (let [profile (th/create-profile* 1 {:is-active true})
        file-id (:id (th/create-file* 1 {:profile-id (:id profile) :project-id (:default-project-id profile)}))
        conn    (open-registered-connection (:id profile))
        peer    (open-registered-connection (:id profile))]
    (try
      (with-held-redis-subscribe
        file-id
        (fn [entered release]
          (subscribe conn :subscribe-file {:file-id file-id :subscription-id (uuid/next)})
          (t/is (true? (deref entered 2000 nil)))
          (ws/handle-message th/*system* (:wsp conn) {:type :close})
          (subscribe peer :subscribe-file {:file-id file-id :subscription-id (uuid/next)})
          (deliver release true)
          (t/is (some? (poll-msg! (:output peer) :file-subscription-ready 2000)))
          (t/is (nil? (poll-msg! (:output conn) :file-subscription-ready revocation-timeout-ms)))))
      (finally
        (close-save-subscriptions conn)
        (close-save-subscriptions peer)))))

(t/deftest file-subscription-confirmation-requires-a-uuid
  (let [profile (th/create-profile* 1 {:is-active true})
        file-id (:id (th/create-file* 1 {:profile-id (:id profile) :project-id (:default-project-id profile)}))
        conn    (open-registered-connection (:id profile))]
    (try
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"subscription-id must be a UUID"
                              (subscribe conn :subscribe-file {:file-id file-id
                                                               :subscription-id "not-a-uuid"})))
      (t/is (nil? (subscribed-file conn)))
      (finally
        (close-save-subscriptions conn)))))

(t/deftest failed-legacy-redis-subscription-is-not-proof-for-an-opt-in
  (let [profile         (th/create-profile* 1 {:is-active true})
        profile-id      (:id profile)
        file-id         (:id (th/create-file* 1 {:id (uuid/next)
                                                 :profile-id profile-id
                                                 :project-id (:default-project-id profile)}))
        legacy          (open-registered-connection profile-id)
        confirmed       (open-registered-connection profile-id)
        subscription-id (uuid/next)
        redis-subscribe rds/subscribe
        failed          (promise)]
    (try
      (with-redefs [rds/subscribe (fn [conn [topic :as topics]]
                                    (if (and (str/ends-with? topic (str "." file-id))
                                             (deliver failed true))
                                      (throw (ex-info "Legacy Redis SUBSCRIBE failed" {}))
                                      (redis-subscribe conn topics)))]
        (subscribe legacy :subscribe-file {:file-id file-id})
        (t/is (true? (deref failed 2000 nil)))
        ;; The legacy path keeps its local entry even though SUBSCRIBE failed.
        ;; An opt-in request cannot use that entry as proof.
        (subscribe confirmed :subscribe-file {:file-id file-id :subscription-id subscription-id})
        (t/is (= {:type :file-subscription-ready :topic file-id :subscription-id subscription-id}
                 (poll-msg! (:output confirmed) :file-subscription-ready 2000)))
        (t/is (nil? (poll-msg! (:output legacy) :file-subscription-ready revocation-timeout-ms)))
        ((publisher!) file-id {:type :file-change :revn 1})
        (t/is (= 1 (:revn (poll-msg! (:output legacy) :file-change 2000))))
        (t/is (= 1 (:revn (poll-msg! (:output confirmed) :file-change 2000)))))
      (finally
        (close-save-subscriptions legacy)
        (close-save-subscriptions confirmed)))))

(t/deftest revocation-closes-subscriptions-that-are-no-longer-authorized
  (let [{:keys [editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)})
                   (subscribe :subscribe-team {:team-id (:id team)}))]

      (t/testing "both subscriptions are open before the revocation"
        (t/is (= (:id file) (subscribed-file conn)))
        (t/is (= (:id team) (subscribed-team conn))))

      ;; A direct row delete fires no command and publishes no
      ;; announcement, so the explicit revalidation below is the only
      ;; thing that can close these subscriptions and the count is
      ;; deterministic. A command would announce, and the live watcher
      ;; could close them before the count is taken.
      (t/testing "the member is removed straight over SQL, so nothing announces it"
        (delete-team-membership! (:id team) (:id editor))
        (t/is (= (:id file) (subscribed-file conn)))
        (t/is (= (:id team) (subscribed-team conn))))

      (t/testing "revalidation closes the file and the team subscription"
        (t/is (= 2 (ws/revalidate-profile-subscriptions
                    th/*system* (:id editor)))))

      (t/testing "nothing is left subscribed to relay content"
        (t/is (nil? (subscribed-file conn)))
        (t/is (nil? (subscribed-team conn))))

      (t/testing "revalidating again is a no-op"
        (t/is (zero? (ws/revalidate-profile-subscriptions
                      th/*system* (:id editor))))))))

(t/deftest revocation-keeps-subscriptions-that-are-still-authorized
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)})
                   (subscribe :subscribe-team {:team-id (:id team)}))]

      (t/testing "the editor is downgraded to viewer, which can still read"
        (let [result (th/command! {::th/type :update-team-member-role
                                   ::rpc/profile-id (:id owner)
                                   :team-id (:id team)
                                   :member-id (:id editor)
                                   :role :viewer})]
          (t/is (th/success? result))))

      (t/testing "revalidation closes nothing"
        (t/is (zero? (ws/revalidate-profile-subscriptions
                      th/*system* (:id editor)))))

      (t/testing "both subscriptions survive"
        (t/is (= (:id file) (subscribed-file conn)))
        (t/is (= (:id team) (subscribed-team conn)))))))

(t/deftest revocation-leaves-other-profiles-alone
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)
        other-owner  (th/create-profile* 3 {:is-active true})
        other-editor (th/create-profile* 4 {:is-active true})
        other-team   (th/create-team* 3 {:profile-id (:id other-owner)})]
    (th/create-team-role* {:team-id (:id other-team)
                           :profile-id (:id other-editor)
                           :role :editor})
    (let [other-file (th/create-file* 3 {:profile-id (:id other-editor)
                                         :project-id (:default-project-id
                                                      other-editor)})]

      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-file {:file-id (:id file)}))
            bystander (-> (open-registered-connection (:id other-editor))
                          (subscribe :subscribe-file {:file-id (:id other-file)}))]

        (th/command! {::th/type :delete-team-member
                      ::rpc/profile-id (:id owner)
                      :team-id (:id team)
                      :member-id (:id editor)})

        (ws/revalidate-profile-subscriptions th/*system* (:id editor))

        (t/testing "the revoked member's subscription is closed"
          (t/is (nil? (subscribed-file conn))))

        (t/testing "a member of another team keeps their subscription"
          (t/is (= (:id other-file) (subscribed-file bystander))))

        (t/testing "revalidating a profile with no connections is safe"
          (t/is (zero? (ws/revalidate-profile-subscriptions
                        th/*system* (uuid/next)))))))))

(t/deftest watcher-consumes-revocation-events-from-the-bus
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)}))]

      ;; Access is taken away the way an external change would take
      ;; it: a direct row delete fires no command and publishes no
      ;; announcement, so the "still subscribed" check cannot race an
      ;; after-commit hook, and the command's own announcement cannot
      ;; mask a broken watcher.
      (delete-team-membership! (:id team) (:id editor))

      (t/testing "the event is not applied until it is announced"
        (t/is (= (:id file) (subscribed-file conn))))

      (ntf/notify-permissions-changed th/*system* (:id editor))

      (t/testing "the watcher closes the subscription off the bus"
        (t/is (wait-until #(nil? (subscribed-file conn)) 3000))))))

(t/deftest internal-revocation-events-are-not-delivered-to-clients
  (let [profile (th/create-profile* 1 {:is-active true})
        id      (uuid/next)
        output  (sp/chan :buf (sp/dropping-buffer 64))
        wsp     (assoc (make-wsp (:id profile) (atom {}) output)
                       ::ws/id id)]

    (ws/register-connection id wsp)
    ((get-method ws/handle-message :open) th/*system* wsp nil)

    (t/testing "the client does receive its own profile traffic"
      (t/is (some? (poll-published!
                    output
                    #(mbus/pub! (::mbus/msgbus th/*system*)
                                :topic (:id profile)
                                :message {:type :notification :text "hello"})
                    :notification
                    revocation-timeout-ms))))

    (t/testing "an internal revocation event never reaches the client"
      (ntf/notify-permissions-changed th/*system* (:id profile))
      (t/is (nil? (poll-msg! output :profile-permissions-changed
                             revocation-timeout-ms))))))


;; --- MUTATIONS THAT ANNOUNCE THE CHANGE
;;
;; The watcher only helps if the mutations that can revoke access
;; actually announce it. Each of these asserts the announcement reaches
;; the bus, and that a change which does not remove access announces
;; nothing harmful.

(defn- captured-events
  "Runs `f` collecting the revocation events published on the internal
  topic, while letting every other publish reach the real bus untouched."
  [f]
  (let [events (atom [])
        real   mbus/pub!]
    (with-redefs [mbus/pub! (fn [bus & {:keys [topic message] :as opts}]
                              (when (= ntf/internal-revocation-topic topic)
                                (swap! events conj message))
                              (apply real bus (mapcat identity opts)))]
      (f))
    @events))

(defn- event-for
  [events profile-id]
  (first (filter #(= profile-id (:profile-id %)) events)))

(t/deftest removing-a-member-announces-the-revocation
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})
    (let [events (captured-events
                  #(th/command! {::th/type :delete-team-member
                                 ::rpc/profile-id (:id owner)
                                 :team-id (:id team)
                                 :member-id (:id editor)}))]
      (t/is (= {:type :profile-permissions-changed
                :profile-id (:id editor)}
               (event-for events (:id editor)))))))

(t/deftest a-role-change-announces-and-still-closes-nothing
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})
    (let [project (th/create-project* 1 {:profile-id (:id editor)
                                         :team-id (:id team)})
          file    (th/create-file* 1 {:profile-id (:id editor)
                                      :project-id (:id project)})
          events  (captured-events
                   #(th/command! {::th/type :update-team-member-role
                                  ::rpc/profile-id (:id owner)
                                  :team-id (:id team)
                                  :member-id (:id editor)
                                  :role :viewer}))]

      (t/testing "the change is announced so subscriptions get re-checked"
        (t/is (some? (event-for events (:id editor)))))

      (t/testing "a viewer can still read, so the subscription survives"
        (let [conn (-> (open-registered-connection (:id editor))
                       (subscribe :subscribe-file {:file-id (:id file)}))]
          (t/is (zero? (ws/revalidate-profile-subscriptions
                        th/*system* (:id editor))))
          (t/is (some? (subscribed-file conn))))))))

(t/deftest deleting-a-file-announces-to-the-whole-team
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})
    (let [project (th/create-project* 1 {:profile-id (:id editor)
                                         :team-id (:id team)})
          file    (th/create-file* 1 {:profile-id (:id editor)
                                      :project-id (:id project)})
          events  (captured-events
                   #(th/command! {::th/type :delete-file
                                  ::rpc/profile-id (:id owner)
                                  :id (:id file)}))]

      ;; One event per team: the watcher resolves the members itself, so
      ;; a large team cannot overflow the bounded publish buffers.
      (t/is (= [{:type :team-permissions-changed
                 :team-id (:id team)}]
               events)))))

(t/deftest deleting-a-team-announces-once
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})
    (let [events (captured-events
                  #(th/command! {::th/type :delete-team
                                 ::rpc/profile-id (:id owner)
                                 :id (:id team)}))]
      (t/is (= [{:type :team-permissions-changed
                 :team-id (:id team)}]
               events)))))

(t/deftest team-event-closes-every-member-subscription
  (let [{:keys [editor team file]} (editor-in-team! 1 2)
        other (th/create-profile* 3 {:is-active true})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id other)
                           :role :editor})
    (let [other-project (th/create-project* 3 {:profile-id (:id other)
                                               :team-id (:id team)})
          other-file    (th/create-file* 3 {:profile-id (:id other)
                                            :project-id (:id other-project)})]
      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-file {:file-id (:id file)}))
            conn2 (-> (open-registered-connection (:id other))
                      (subscribe :subscribe-file {:file-id (:id other-file)}))]

        ;; Same shape as the watcher test above: a direct soft-delete
        ;; publishes nothing, so this test owns the fan-out of one event
        ;; and the announcement of `delete-team` is left to
        ;; `deleting-a-team-announces-once`. A second notify here would
        ;; make the test pass even if that announcement disappeared.
        (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})

        (t/testing "nothing closes until the event arrives"
          (t/is (some? (subscribed-file conn)))
          (t/is (some? (subscribed-file conn2))))

        (t/testing "one team event reaches the watcher off the bus"
          (ntf/notify-team-permissions-changed th/*system* (:id team))
          (t/is (wait-until #(nil? (subscribed-file conn)) 3000))
          (t/is (wait-until #(nil? (subscribed-file conn2)) 3000)))))))

(t/deftest rolled-back-revocation-announces-nothing
  (let [editor (th/create-profile* 2 {:is-active true})
        events (captured-events
                #(try
                   (db/tx-run! th/*system*
                               (fn [_cfg]
                                 (ntf/notify-permissions-changed th/*system* (:id editor))
                                 (throw (ex-info "boom" {}))))
                   (catch clojure.lang.ExceptionInfo _)))]
    (t/is (empty? events))))

(t/deftest leaving-a-team-announces-and-tells-only-the-leaver
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        other  (th/create-profile* 3 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id other)
                           :role :editor})
    (let [published (atom [])
          real      mbus/pub!]
      (with-redefs [mbus/pub! (fn [bus & args]
                                (let [opts (apply hash-map args)]
                                  (swap! published conj opts))
                                (apply real bus args))]
        (th/command! {::th/type :leave-team
                      ::rpc/profile-id (:id editor)
                      :id (:id team)}))

      (t/testing "the leaver is told they were removed, so the client leaves"
        (let [msg (->> @published
                       (filter #(= (:id editor) (:topic %)))
                       (map :message)
                       first)]
          (t/is (= :team-membership-change (:type msg)))
          (t/is (= :removed (:change msg)))
          (t/is (= (:id team) (:team-id msg)))))

      (t/testing "no other member is told they were removed"
        (t/is (empty? (filter #(= (:id other) (:topic %)) @published)))))))

(t/deftest deleting-a-share-link-announces-nothing
  ;; A share-link visitor never passes the websocket subscribe check:
  ;; `check-read-permissions!` is called without a share-id, and that
  ;; arity only considers team membership. There is no subscription to
  ;; revoke, so announcing would be noise.
  (let [owner (th/create-profile* 1 {:is-active true})
        file  (th/create-file* 1 {:profile-id (:id owner)
                                  :project-id (:default-project-id owner)})]
    (th/command! {::th/type :create-share-link
                  ::rpc/profile-id (:id owner)
                  :file-id (:id file)
                  :who-comment "none"
                  :who-inspect "none"
                  :pages #{}})
    (let [link-id (:id (db/exec-one! th/*system*
                                     ["select id from share_link where file_id = ?"
                                      (:id file)]))
          events  (captured-events
                   #(th/command! {::th/type :delete-share-link
                                  ::rpc/profile-id (:id owner)
                                  :id link-id}))]
      (t/is (some? link-id))
      (t/is (empty? events)))))

;; --- PERIODIC REVALIDATION
;;
;; The announcement path is the primary mechanism. This interval is the
;; safety net for access changes that fire no command here, such as
;; Nitrate transferring the ownership of an organization, or rows
;; deleted straight over SQL.

(t/deftest revalidation-interval-defaults-to-five-minutes
  (t/is (= (ct/duration {:minutes 5}) ws/default-revalidation-interval))
  (t/is (= (ct/duration {:minutes 5}) (ws/revalidation-interval))))

(t/deftest revalidation-is-configurable
  (with-redefs [cf/get (fn [k default] (if (= :subscription-revalidation-interval k)
                                         (ct/duration {:seconds 30})
                                         default))]
    (t/is (= (ct/duration {:seconds 30}) (ws/revalidation-interval)))))

(t/deftest interval-recheck-drops-a-subscription-without-any-announcement
  (let [{:keys [editor team file]} (editor-in-team! 1 2)]

    (with-redefs [cf/get (fn [k default]
                           (if (= :subscription-revalidation-interval k)
                             (ct/duration {:millis 150})
                             default))]
      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-file {:file-id (:id file)}))
            out (:output conn)]

        (t/testing "the subscription is live at first"
          (t/is (some? (subscribed-file conn))))

        ;; Access is taken away the way an external change would take
        ;; it: a direct row delete fires no command and publishes no
        ;; announcement, so the interval is the only thing that can
        ;; close the subscription.
        (delete-team-membership! (:id team) (:id editor))

        (t/testing "the interval re-check drops it anyway"
          (t/is (wait-until #(nil? (subscribed-file conn)) 3000)))

        (t/testing "and nothing reaches the client afterwards"
          (mbus/pub! (::mbus/msgbus th/*system*)
                     :topic (:id file)
                     :message {:type :file-change :file-id (:id file) :revn 9})
          (t/is (nil? (poll-msg! out :file-change
                                 revocation-timeout-ms))))))))

(t/deftest interval-recheck-keeps-a-subscription-that-still-holds
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (with-redefs [cf/get (fn [k default]
                           (if (= :subscription-revalidation-interval k)
                             (ct/duration {:millis 150})
                             default))]
      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-file {:file-id (:id file)}))
            out (:output conn)]

        (t/testing "a downgrade to viewer still allows reading"
          (t/is (th/success? (th/command! {::th/type :update-team-member-role
                                           ::rpc/profile-id (:id owner)
                                           :team-id (:id team)
                                           :member-id (:id editor)
                                           :role :viewer}))))

        ;; Wait for at least two interval ticks, so a re-check that
        ;; wrongly closed the subscription would have run.
        (Thread/sleep 500)

        (t/testing "the subscription survives the interval"
          (t/is (some? (subscribed-file conn))))

        (t/testing "and traffic still flows"
          (t/is (some? (poll-published!
                        out
                        #(mbus/pub! (::mbus/msgbus th/*system*)
                                    :topic (:id file)
                                    :message {:type :file-change
                                              :file-id (:id file)
                                              :revn 3})
                        :file-change
                        revocation-timeout-ms))))))))

(t/deftest interval-recheck-covers-the-team-subscription-too
  (let [{:keys [editor team]} (editor-in-team! 1 2)]

    (with-redefs [cf/get (fn [k default]
                           (if (= :subscription-revalidation-interval k)
                             (ct/duration {:millis 150})
                             default))]
      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-team {:team-id (:id team)}))]

        (t/is (some? (subscribed-team conn)))

        ;; Same external-change shape as above: no command, no
        ;; announcement, only the interval.
        (delete-team-membership! (:id team) (:id editor))

        (t/testing "the team subscription is dropped by the interval"
          (t/is (wait-until #(nil? (subscribed-team conn)) 3000)))))))

(t/deftest a-false-check-closes-the-subscription
  (let [{:keys [editor file]} (editor-in-team! 1 2)]

    (with-redefs [cf/get (fn [k default]
                           (if (= :subscription-revalidation-interval k)
                             (ct/duration {:millis 150})
                             default))]
      (let [conn (-> (open-registered-connection (:id editor))
                     (subscribe :subscribe-file {:file-id (:id file)}))]

        (with-redefs [files/has-read-permissions? (fn [& _] false)]
          (t/testing "a check that returns false closes it"
            (t/is (wait-until #(nil? (subscribed-file conn)) 3000))))))))

(t/deftest resubscribe-stops-previous-revalidation
  (let [profile (th/create-profile* 1 {:is-active true})
        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]

    (with-redefs [cf/get (fn [k default]
                           (if (= :subscription-revalidation-interval k)
                             (ct/duration {:millis 150})
                             default))]
      (let [counts (atom {})
            real   files/has-read-permissions?]
        (with-redefs [files/has-read-permissions?
                      (fn [cfg pid fid]
                        (swap! counts update fid (fnil inc 0))
                        (real cfg pid fid))]
          (let [conn (-> (open-registered-connection (:id profile))
                         (subscribe :subscribe-file {:file-id (:id file1)}))]

            (t/testing "the first subscription is re-checked on the interval"
              (t/is (wait-until #(pos? (get @counts (:id file1) 0)) 3000)))

            (t/testing "resubscribing moves the relay to the new file"
              (subscribe conn :subscribe-file {:file-id (:id file2)})
              (t/is (wait-until #(>= (get @counts (:id file2) 0) 2) 3000)))

            (t/testing "the previous subscription stops revalidating"
              (let [old (get @counts (:id file1) 0)]
                (Thread/sleep 400)
                (t/is (= old (get @counts (:id file1) 0)))))))))))

;; --- EFFECT TESTS
;;
;; Announcing is not the same as revoking: these assert the subscription
;; actually drops and the content stops flowing.

(t/deftest deleting-a-file-closes-open-subscriptions
  (let [{:keys [owner editor file]} (editor-in-team! 1 2)
        publish (publisher!)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)}))
          out (:output conn)]

      (th/command! {::th/type :delete-file
                    ::rpc/profile-id (:id owner)
                    :id (:id file)})

      (t/testing "the file subscription drops without any manual publish"
        (t/is (wait-until #(nil? (subscribed-file conn)) 3000)))

      (t/testing "no file content reaches the client afterwards"
        (publish (:id file) {:type :file-change :file-id (:id file) :revn 9})
        (t/is (nil? (poll-msg! out :file-change revocation-timeout-ms)))))))

(t/deftest deleting-a-project-closes-open-subscriptions
  (let [{:keys [owner editor file]} (editor-in-team! 1 2)
        project-id (:project-id file)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)}))
          out (:output conn)]

      (th/command! {::th/type :delete-project
                    ::rpc/profile-id (:id owner)
                    :id project-id})

      (t/testing "the file subscription drops"
        (t/is (wait-until #(nil? (subscribed-file conn)) 3000)))

      (t/testing "no file content reaches the client afterwards"
        (mbus/pub! (::mbus/msgbus th/*system*)
                   :topic (:id file)
                   :message {:type :file-change :file-id (:id file) :revn 9})
        (t/is (nil? (poll-msg! out :file-change revocation-timeout-ms)))))))

(t/deftest deleting-a-team-closes-open-subscriptions
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)})
                   (subscribe :subscribe-team {:team-id (:id team)}))]

      (th/command! {::th/type :delete-team
                    ::rpc/profile-id (:id owner)
                    :id (:id team)})

      (t/testing "both subscriptions drop"
        (t/is (wait-until #(nil? (subscribed-file conn)) 3000))
        (t/is (wait-until #(nil? (subscribed-team conn)) 3000))))))

(t/deftest moving-a-file-closes-source-team-subscriptions
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team-a (th/create-team* 1 {:profile-id (:id owner)})
        team-b (th/create-team* 2 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team-a)
                           :profile-id (:id editor)
                           :role :editor})
    (let [project-a (th/create-project* 1 {:profile-id (:id owner)
                                           :team-id (:id team-a)})
          project-b (th/create-project* 2 {:profile-id (:id owner)
                                           :team-id (:id team-b)})
          file      (th/create-file* 1 {:profile-id (:id editor)
                                        :project-id (:id project-a)})
          conn      (-> (open-registered-connection (:id editor))
                        (subscribe :subscribe-file {:file-id (:id file)}))]

      (th/command! {::th/type :move-files
                    ::rpc/profile-id (:id owner)
                    :ids #{(:id file)}
                    :project-id (:id project-b)})

      (t/testing "a member left behind in the source team loses the subscription"
        (t/is (wait-until #(nil? (subscribed-file conn)) 3000))))))

(t/deftest moving-a-project-closes-source-team-subscriptions
  (let [owner  (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        team-a (th/create-team* 1 {:profile-id (:id owner)})
        team-b (th/create-team* 2 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team-a)
                           :profile-id (:id editor)
                           :role :editor})
    (let [project-a (th/create-project* 1 {:profile-id (:id owner)
                                           :team-id (:id team-a)})
          project-b (th/create-project* 2 {:profile-id (:id owner)
                                           :team-id (:id team-b)})
          file      (th/create-file* 1 {:profile-id (:id editor)
                                        :project-id (:id project-a)})
          conn      (-> (open-registered-connection (:id editor))
                        (subscribe :subscribe-file {:file-id (:id file)})
                        (subscribe :subscribe-team {:team-id (:id team-a)}))]

      (th/command! {::th/type :move-project
                    ::rpc/profile-id (:id owner)
                    :team-id (:id team-b)
                    :project-id (:id project-a)})

      (t/testing "the file subscription drops: the file only lives in team-b now"
        (t/is (wait-until #(nil? (subscribed-file conn)) 3000)))

      (t/testing "the team subscription survives: the editor is still a member of team-a"
        (t/is (= (:id team-a) (subscribed-team conn)))))))

(t/deftest close-team-subscription-is-idempotent
  (let [profile (th/create-profile* 1 {:is-active true})
        team    (th/create-team* 1 {:profile-id (:id profile)})
        other   (th/create-team* 2 {:profile-id (:id profile)})
        output  (sp/chan :buf (sp/dropping-buffer 64))
        relay   (sp/chan :buf (sp/dropping-buffer 64))
        ws-state (atom {::ws/team-subscription
                        {:team-id (:id team)
                         :channel relay
                         :topic (:id team)}})
        wsp     (assoc (make-wsp (:id profile) ws-state output)
                       ::ws/state ws-state)]

    (t/testing "closing a team the connection is not subscribed to does nothing"
      (with-redefs [mbus/purge! (fn [& _] (throw ::unexpected-purge))]
        (t/is (nil? (ws/close-team-subscription th/*system* wsp (:id other))))
        (t/is (some? (::ws/team-subscription @ws-state)))))

    (t/testing "closing the subscribed team purges the bus and the relay stops"
      (let [purged (atom [])]
        (with-redefs [mbus/purge! (fn [_ chans] (swap! purged conj chans))]
          (ws/close-team-subscription th/*system* wsp (:id team))
          (t/is (= 1 (count @purged)))))
      (t/is (nil? (sp/poll! relay)))
      (t/is (true? (sp/closed? relay))))

    (t/testing "the subscription is forgotten, so closing again is a no-op"
      (t/is (nil? (::ws/team-subscription @ws-state)))
      (t/is (nil? (ws/close-team-subscription th/*system* wsp (:id team)))))))

(t/deftest losing-a-team-keeps-the-other-file-subscription
  (let [{:keys [owner editor team]} (editor-in-team! 1 2)
        other-team (th/create-team* 3 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id other-team)
                           :profile-id (:id editor)
                           :role :editor})
    (let [other-project (th/create-project* 3 {:profile-id (:id editor)
                                               :team-id (:id other-team)})
          other-file    (th/create-file* 3 {:profile-id (:id editor)
                                            :project-id (:id other-project)})
          conn          (-> (open-registered-connection (:id editor))
                            (subscribe :subscribe-team {:team-id (:id team)})
                            (subscribe :subscribe-file {:file-id (:id other-file)}))]

      (th/command! {::th/type :delete-team-member
                    ::rpc/profile-id (:id owner)
                    :team-id (:id team)
                    :member-id (:id editor)})

      (t/testing "the lost team subscription drops"
        (t/is (wait-until #(nil? (subscribed-team conn)) 3000)))

      (t/testing "the file subscription on the other team survives"
        (t/is (some? (subscribed-file conn)))))))

;; The revocation watcher is the only integrant key here, so it is the
;; only one that compiles this schema at boot: a ref with no registered
;; schema crashes the boot with :malli.core/invalid-schema instead of
;; asserting. The test system builds the component, but assert-key only
;; runs its check with *assert* set, so pin the compilation here.
(t/deftest revocation-watcher-schema-resolves-every-ref
  (t/testing "the schema compiles"
    (t/is (sm/schema? (sm/schema @#'ws/schema:revocation-watcher))))

  (t/testing "every ref the key injects is registered"
    (t/is (= #{::db/pool ::mbus/msgbus}
             (disj (set (sm/keys @#'ws/schema:revocation-watcher))
                   :app.nitrate/client))))
  (t/testing "the nitrate client is optional, so a nil one passes"
    (t/is (sm/valid? @#'ws/schema:revocation-watcher
                     {::db/pool (:app.db/pool th/*system*)
                      ::mbus/msgbus (:app.msgbus/msgbus th/*system*)})))
  (t/testing "a non-map nitrate client is rejected"
    (t/is (not (sm/valid? @#'ws/schema:revocation-watcher
                          {::db/pool (:app.db/pool th/*system*)
                           ::mbus/msgbus (:app.msgbus/msgbus th/*system*)
                           :app.nitrate/client "not-a-client"})))))

(t/deftest organization-owner-keeps-read-only-access
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)}))]

      ;; The admin-console flag is off in tests, so Nitrate never grants
      ;; this; stand in for an organization owner expelled from the team.
      (with-redefs [nitrate/organization-owner-of-team? (fn [& _] true)]
        (th/command! {::th/type :delete-team-member
                      ::rpc/profile-id (:id owner)
                      :team-id (:id team)
                      :member-id (:id editor)})

        (t/testing "revalidation closes nothing for an organization owner"
          (t/is (zero? (ws/revalidate-profile-subscriptions
                        th/*system* (:id editor)))))

        (t/testing "the read-only subscription survives"
          (t/is (some? (subscribed-file conn))))))))

(t/deftest soft-deleted-file-closes-org-owner-subscription
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)}))]

      ;; The admin-console flag is off in tests, so Nitrate never grants
      ;; this; stand in for an organization owner expelled from the team.
      (with-redefs [nitrate/organization-owner-of-team? (fn [& _] true)]
        ;; Access is taken away the way an external change would take
        ;; it: a direct row soft-delete fires no command and publishes no
        ;; announcement. Membership SQL grants nothing through a deleted
        ;; file, but the org-owner fallback does not look at deleted_at,
        ;; so only the existence gate can close the subscription.
        (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id file)})

        (t/testing "revalidation closes the subscription although Nitrate still grants read-only"
          (t/is (= 1 (ws/revalidate-profile-subscriptions
                      th/*system* (:id editor)))))
        (t/testing "the read-only subscription is gone"
          (t/is (nil? (subscribed-file conn))))))))

(t/deftest soft-deleted-team-closes-org-owner-subscription
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-team {:team-id (:id team)}))]

      ;; Same shape as the file case: the org-owner fallback grants
      ;; read-only access without looking at the team's deleted_at.
      (with-redefs [nitrate/organization-owner-of-team? (fn [& _] true)]
        (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})

        (t/testing "revalidation closes the subscription although Nitrate still grants read-only"
          (t/is (= 1 (ws/revalidate-profile-subscriptions
                      th/*system* (:id editor)))))
        (t/testing "the read-only subscription is gone"
          (t/is (nil? (subscribed-team conn))))))))

(t/deftest soft-deleted-project-closes-org-owner-file-subscription
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)}))]

      ;; The admin-console flag is off in tests, so Nitrate never grants
      ;; this; stand in for an organization owner expelled from the team.
      (with-redefs [nitrate/organization-owner-of-team? (fn [& _] true)]
        ;; A direct row soft-delete fires no command and publishes no
        ;; announcement. Membership SQL denies through a deleted project,
        ;; but the org-owner fallback resolves the team without any
        ;; deleted_at filter, so only the root fix can close the file
        ;; subscription.
        (db/update! th/*system* :project {:deleted-at (ct/now)}
                    {:id (:project-id file)})

        (t/testing "revalidation closes the file subscription although Nitrate still grants read-only"
          (t/is (= 1 (ws/revalidate-profile-subscriptions
                      th/*system* (:id editor)))))
        (t/testing "the read-only subscription is gone"
          (t/is (nil? (subscribed-file conn))))))))

(t/deftest soft-deleted-team-closes-org-owner-file-subscription
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (let [conn (-> (open-registered-connection (:id editor))
                   (subscribe :subscribe-file {:file-id (:id file)}))]

      ;; Same shape with the ancestor unavailable at the team level:
      ;; membership denies (t.deleted_at is null), the org-owner
      ;; fallback grants, and only resolve-team-id's team-row check
      ;; can close the file subscription.
      (with-redefs [nitrate/organization-owner-of-team? (fn [& _] true)]
        (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})

        (t/testing "revalidation closes the file subscription although Nitrate still grants read-only"
          (t/is (= 1 (ws/revalidate-profile-subscriptions
                      th/*system* (:id editor)))))
        (t/testing "the read-only subscription is gone"
          (t/is (nil? (subscribed-file conn))))))))

;; --- POLLING HELPERS

(t/deftest a-delivery-assertion-republishes-when-the-subscription-lands-late
  ;; The subscription reaches redis through an asynchronous action, so the
  ;; first publish of a delivery assertion can be lost before redis knows
  ;; the subscription. The assertion republishes: this pins that it does,
  ;; and that it stops as soon as a message arrives.
  (let [ch        (sp/chan :buf (sp/dropping-buffer 8))
        published (atom 0)
        publish!  (fn []
                    (when (= 2 (swap! published inc))
                      (sp/put! ch {:type :notification :text "hello"})))]
    (t/is (some? (poll-published! ch publish! :notification 50)))
    (t/is (= 2 @published)
          "the second publish is the last one, once the message arrived")))
