;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-websocket-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.websocket :as ws]
   [app.msgbus :as mbus]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.util.websocket :as util-ws]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [promesa.exec.csp :as sp]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

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

(defn- poll-msg!
  "Drains `ch` for up to `ms`, returning the first message whose `:type`
  is `type`, or nil when none arrives.

  Draining rather than reading a single message matters for the negative
  assertions: a live subscription also carries presence traffic, so
  \"no `:file-change` arrived\" is the property under test, not \"the
  channel stayed empty\"."
  [ch type ms]
  (let [result (promise)
        deadline (+ (System/currentTimeMillis) ms)]
    (sp/go
      (loop []
        (let [remain (- deadline (System/currentTimeMillis))]
          (when (pos? remain)
            (let [[msg _] (sp/alts! [ch (sp/timeout-chan remain)])]
              (if (= type (:type msg))
                (deliver result msg)
                (recur)))))))
    (deref result (inc ms) nil)))

(defn- open-subscription!
  "Runs `handler` for `profile-id` against a live system, returning the
  output channel that receives whatever the subscription forwards."
  [handler profile-id params]
  (let [output-ch (sp/chan :buf (sp/dropping-buffer 64))
        state     (atom {})
        wsp       (make-wsp profile-id state output-ch)]
    ((get-method ws/handle-message handler) th/*system* wsp params)
    output-ch))

(defn- wait-until
  "Polls `pred` until it holds or `ms` elapses. Returns whether it held."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (pred)                                   true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 25) (recur))))))

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

(defn- subscribe
  [{:keys [wsp] :as conn} handler params]
  ((get-method ws/handle-message handler) th/*system* wsp params)
  conn)

(defn- subscribed-file [conn] (-> @(:state conn) ::ws/file-subscription :file-id))
(defn- subscribed-team [conn] (-> @(:state conn) ::ws/team-subscription :team-id))

(defn- publisher!
  "Returns a fn that publishes `message` on `topic` through the system
  msgbus, the same path `send-notifications!` uses."
  []
  (let [msgbus (::mbus/msgbus th/*system*)]
    (fn [topic message]
      (mbus/pub! msgbus :topic topic :message message))))

;; --- CONNECTION REGISTRY

(defn- with-clean-registry
  "Runs `f` against an empty connection registry, restoring the previous
  contents afterwards so tests cannot leak connections into each other."
  [f]
  (let [saved @ws/state]
    (reset! ws/state {:connections {} :by-profile {}})
    (try (f)
         (finally (reset! ws/state saved)))))

(t/deftest registry-indexes-connections-by-profile
  (let [profile-a (uuid/next)
        profile-b (uuid/next)
        conn-a    (uuid/next)
        conn-b    (uuid/next)
        conn-c    (uuid/next)]

    (with-clean-registry
      (fn []
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
          (t/is (= conn-c (-> (:connections @ws/state) keys first))))))))

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
                   :changes [{:type :add :id (uuid/next)}]}
          out     (open-subscription! :subscribe-file (:id editor)
                                      {:file-id file-id})]

      (t/testing "member receives file changes before revocation"
        (publish file-id change)
        (t/is (some? (poll-msg! out :file-change revocation-timeout-ms))))

      (t/testing "owner removes the member from the team"
        (let [result (th/command! {::th/type :delete-team-member
                                   ::rpc/profile-id (:id owner)
                                   :team-id (:id team)
                                   :member-id (:id editor)})]
          (t/is (th/success? result))))

      (t/testing "revoked member stops receiving file changes"
        (publish file-id (assoc change :revn 2))
        (t/is (nil? (poll-msg! out :file-change revocation-timeout-ms)))))))

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
                   :changes [{:type :add :id (uuid/next)}]}
          out     (open-subscription! :subscribe-team (:id editor)
                                      {:team-id team-id})]

      (t/testing "member receives library changes before revocation"
        (publish team-id change)
        (t/is (some? (poll-msg! out :library-change revocation-timeout-ms))))

      (t/testing "member leaves the team"
        (let [result (th/command! {::th/type :leave-team
                                   ::rpc/profile-id (:id editor)
                                   :id team-id})]
          (t/is (th/success? result))))

      (t/testing "revoked member stops receiving library changes"
        (publish team-id (assoc change :revn 2))
        (t/is (nil? (poll-msg! out :library-change revocation-timeout-ms)))))))

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
        (publish file-id change)
        (t/is (some? (poll-msg! out :file-change revocation-timeout-ms)))))))

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

(t/deftest revocation-closes-subscriptions-that-are-no-longer-authorized
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (with-clean-registry
      (fn []
        (let [conn (-> (open-registered-connection (:id editor))
                       (subscribe :subscribe-file {:file-id (:id file)})
                       (subscribe :subscribe-team {:team-id (:id team)}))]

          (t/testing "both subscriptions are open before the revocation"
            (t/is (= (:id file) (subscribed-file conn)))
            (t/is (= (:id team) (subscribed-team conn))))

          (t/testing "the member is removed from the team"
            (let [result (th/command! {::th/type :delete-team-member
                                       ::rpc/profile-id (:id owner)
                                       :team-id (:id team)
                                       :member-id (:id editor)})]
              (t/is (th/success? result))))

          (t/testing "revalidation closes the file and the team subscription"
            (t/is (= 2 (ws/revalidate-profile-subscriptions
                        th/*system* (:id editor)))))

          (t/testing "nothing is left subscribed to relay content"
            (t/is (nil? (subscribed-file conn)))
            (t/is (nil? (subscribed-team conn))))

          (t/testing "revalidating again is a no-op"
            (t/is (zero? (ws/revalidate-profile-subscriptions
                          th/*system* (:id editor))))))))))

(t/deftest revocation-keeps-subscriptions-that-are-still-authorized
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (with-clean-registry
      (fn []
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
            (t/is (= (:id team) (subscribed-team conn)))))))))

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

      (with-clean-registry
        (fn []
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
                            th/*system* (uuid/next)))))))))))

(t/deftest watcher-consumes-revocation-events-from-the-bus
  (let [{:keys [owner editor team file]} (editor-in-team! 1 2)]

    (with-clean-registry
      (fn []
        (let [conn (-> (open-registered-connection (:id editor))
                       (subscribe :subscribe-file {:file-id (:id file)}))]

          (th/command! {::th/type :delete-team-member
                        ::rpc/profile-id (:id owner)
                        :team-id (:id team)
                        :member-id (:id editor)})

          (t/testing "the event is not applied until it is announced"
            (t/is (= (:id file) (subscribed-file conn))))

          (ws/notify-permissions-changed th/*system* (:id editor))

          (t/testing "the watcher closes the subscription off the bus"
            (t/is (wait-until #(nil? (subscribed-file conn)) 3000))))))))

(t/deftest internal-revocation-events-are-not-delivered-to-clients
  (let [profile (th/create-profile* 1 {:is-active true})
        id      (uuid/next)
        output  (sp/chan :buf (sp/dropping-buffer 64))
        wsp     (assoc (make-wsp (:id profile) (atom {}) output)
                       ::ws/id id)]

    (ws/register-connection id wsp)
    ((get-method ws/handle-message :open) th/*system* wsp nil)

    (t/testing "the client does receive its own profile traffic"
      (mbus/pub! (::mbus/msgbus th/*system*)
                 :topic (:id profile)
                 :message {:type :notification :text "hello"})
      (t/is (some? (poll-msg! output :notification revocation-timeout-ms))))

    (t/testing "an internal revocation event never reaches the client"
      (ws/notify-permissions-changed th/*system* (:id profile))
      (t/is (nil? (poll-msg! output :profile-permissions-changed
                             revocation-timeout-ms))))))

