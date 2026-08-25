;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-nitrate-test
  (:require
   [app.auth.oidc :as oidc]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.email :as eml]
   [app.http :as-alias http]
   [app.http.errors :as http-errors]
   [app.nitrate :as nitrate]
   [app.rpc :as rpc]
   [app.rpc.commands.nitrate]
   [app.rpc.commands.teams :as teams]
   [app.rpc.helpers :as rph]
   [backend-tests.helpers :as th]
   [buddy.core.codecs :as bc]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-organization-summary
  [& {:keys [organization-id organization-name owner-id your-penpot-teams organization-teams]
      :or   {your-penpot-teams [] organization-teams []}}]
  {:id       organization-id
   :name     organization-name
   :owner-id owner-id
   :teams    (into
              (mapv (fn [id] {:id id :is-your-penpot true}) your-penpot-teams)
              (mapv (fn [id] {:id id :is-your-penpot false}) organization-teams))})

(defn- nitrate-call-mock
  "Creates a mock for nitrate/call that returns the given organization-summary for
  :get-organization-summary, a valid membership for :get-organization-membership, and nil for
  any other method."
  ([organization-summary]
   (nitrate-call-mock organization-summary nil))
  ([organization-summary remove-profile-params]
   (fn [_cfg method params]
     (case method
       :get-organization-summary organization-summary
       :get-organization-membership {:is-member true
                                     :organization-id (:id organization-summary)}
       :remove-profile-from-organization (when remove-profile-params
                                           (reset! remove-profile-params params))
       nil))))

(defn- nitrate-organization-summary-only-mock
  [organization-summary]
  (fn [_cfg method _params]
    (case method
      :get-organization-summary organization-summary
      :get-organization-membership {:is-member true
                                    :organization-id (:id organization-summary)
                                    :created-at (ct/inst "2026-07-17T12:00:00Z")}
      :get-organization-members [(:owner-id organization-summary)
                                 (uuid/random)]
      nil)))

(defn- active-sso-call-mock
  [team-id organization-id organization-owner-id]
  (fn [_cfg method params]
    (case method
      :get-team-organization
      (when (= team-id (:team-id params))
        {:id team-id
         :organization {:id organization-id
                        :owner-id organization-owner-id}})

      :get-organization-sso-by-team
      {:active true
       :issuer "https://idp.example.com"
       :organization-id organization-id}

      nil)))

(defn- unauthorized-sso-mock
  "Creates a mock for nitrate/sso-session-authorized? that reports an active
  SSO the session does not satisfy. Pass nil to leave the organization out of
  the nitrate payload."
  [organization-id]
  (fn [_cfg _organization-id _team-id _request]
    {:authorized false
     :sso (cond-> {:active true
                   :issuer "https://idp.example.com"}
            (some? organization-id)
            (assoc :organization-id organization-id))}))

(defn- sso-gate-error
  "Builds the SSO gate around a handler that must never be reached, and
  returns the exception it raises for `params`."
  [mdata params cfg]
  (let [handler (fn [_cfg _params] ::handler-called)
        wrapped (binding [cf/flags (conj cf/flags :admin-console)]
                  (#'rpc/wrap-nitrate-sso nil handler mdata))]
    (try
      (wrapped cfg (with-meta params {::http/request {}}))
      nil
      (catch Throwable cause
        cause))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest check-nitrate-sso-skips-gate-without-team-access
  (let [team-owner       (th/create-profile* 1 {:is-active true})
        external-profile (th/create-profile* 2 {:is-active true})
        team             (th/create-team* 1 {:profile-id (:id team-owner)})
        organization-id  (uuid/random)
        params           (with-meta
                           {::th/type :check-nitrate-sso
                            ::rpc/profile-id (:id external-profile)
                            :team-id (:id team)
                            :url "https://penpot.example.com/#/workspace"}
                           {::http/request {}})]
    (binding [cf/flags (conj cf/flags :admin-console)]
      (with-redefs [nitrate/call
                    (active-sso-call-mock
                     (:id team)
                     organization-id
                     (:id team-owner))
                    oidc/build-organization-sso-auth-redirect-uri
                    (constantly "https://idp.example.com/authorize")]
        (let [out (th/command! params)]
          (t/is (th/success? out))
          ;; The reason tells the client this is a permission problem, not a
          ;; usable SSO session.
          (t/is (= {:authorized true :reason :no-team-access} (:result out))))))))

(t/deftest check-nitrate-sso-keeps-gate-for-team-member
  (let [team-owner      (th/create-profile* 1 {:is-active true})
        team            (th/create-team* 1 {:profile-id (:id team-owner)})
        organization-id (uuid/random)
        redirect-uri    "https://idp.example.com/authorize"
        redirect-options (atom nil)
        started-event   (atom nil)
        params          (with-meta
                          {::th/type :check-nitrate-sso
                           ::rpc/profile-id (:id team-owner)
                           :team-id (:id team)
                           :url "https://penpot.example.com/#/workspace"}
                          {::http/request {}})]
    (binding [cf/flags (conj cf/flags :admin-console)]
      (with-redefs [nitrate/call
                    (active-sso-call-mock
                     (:id team)
                     organization-id
                     (:id team-owner))
                    oidc/build-organization-sso-auth-redirect-uri
                    (fn [_cfg _sso & options]
                      (reset! redirect-options (apply hash-map options))
                      redirect-uri)
                    oidc/submit-organization-sso-auth-started-event
                    (fn [_cfg _request profile-id received-organization-id]
                      (reset! started-event {:profile-id profile-id
                                             :organization-id received-organization-id}))]
        (let [out (th/command! params)]
          (t/is (th/success? out))
          (t/is (= {:authorized false
                    :redirect-uri redirect-uri}
                   (:result out)))
          (t/is (= #{:dest-url :organization-id} (set (keys @redirect-options))))
          (t/is (= "https://penpot.example.com/#/workspace" (str (:dest-url @redirect-options))))
          (t/is (nil? (:organization-id @redirect-options)))
          (t/is (= {:profile-id (:id team-owner)
                    :organization-id organization-id}
                   @started-event)))))))

(t/deftest check-nitrate-sso-reports-redirect-failure
  (let [profile         (th/create-profile* 1 {:is-active true})
        organization-id (uuid/random)
        cause           (ex-info "provider unavailable" {:response-status-code 503})
        reported        (atom nil)
        params          (with-meta
                          {::th/type :check-nitrate-sso
                           ::rpc/profile-id (:id profile)
                           :organization-id organization-id
                           :url "https://penpot.example.com/#/workspace"}
                          {::http/request {}})]
    (binding [cf/flags (conj cf/flags :admin-console)]
      (with-redefs [nitrate/sso-session-authorized? (unauthorized-sso-mock organization-id)
                    oidc/build-organization-sso-auth-redirect-uri (fn [& _] (throw cause))
                    oidc/submit-organization-sso-auth-failed-event
                    (fn [_cfg _request profile-id received-organization-id received-cause]
                      (reset! reported {:profile-id profile-id
                                        :organization-id received-organization-id
                                        :cause received-cause}))]
        (let [out (th/command! params)]
          (t/is (not (th/success? out)))
          (t/is (= {:profile-id (:id profile)
                    :organization-id organization-id
                    :cause cause}
                   @reported)))))))

(t/deftest check-nitrate-sso-keeps-gate-for-non-member-organization-owner
  (let [team-owner      (th/create-profile* 1 {:is-active true})
        organization-owner       (th/create-profile* 2 {:is-active true})
        team            (th/create-team* 1 {:profile-id (:id team-owner)})
        organization-id (uuid/random)
        redirect-uri    "https://idp.example.com/authorize"
        params          (with-meta
                          {::th/type :check-nitrate-sso
                           ::rpc/profile-id (:id organization-owner)
                           :team-id (:id team)
                           :url "https://penpot.example.com/#/workspace"}
                          {::http/request {}})]
    (binding [cf/flags (conj cf/flags :admin-console)]
      (with-redefs [nitrate/organization-owner-of-team?
                    (fn [_cfg profile-id team-id]
                      (and (= (:id organization-owner) profile-id)
                           (= (:id team) team-id)))
                    nitrate/call
                    (active-sso-call-mock
                     (:id team)
                     organization-id
                     (:id organization-owner))
                    oidc/build-organization-sso-auth-redirect-uri
                    (constantly redirect-uri)]
        (let [out (th/command! params)]
          (t/is (th/success? out))
          (t/is (= {:authorized false
                    :redirect-uri redirect-uri}
                   (:result out))))))))

(t/deftest check-nitrate-sso-reports-a-satisfied-gate-for-a-valid-session
  (let [team-owner      (th/create-profile* 1 {:is-active true})
        team            (th/create-team* 1 {:profile-id (:id team-owner)})
        organization-id (uuid/random)
        params          (with-meta
                          {::th/type :check-nitrate-sso
                           ::rpc/profile-id (:id team-owner)
                           :team-id (:id team)
                           :url "https://penpot.example.com/#/workspace"}
                          {::http/request {}})]
    (binding [cf/flags (conj cf/flags :admin-console)]
      (with-redefs [nitrate/sso-session-authorized?
                    (fn [_cfg _organization-id _team-id _request]
                      {:authorized true
                       :sso {:active true
                             :issuer "https://idp.example.com"
                             :organization-id organization-id}})]
        (let [out (th/command! params)]
          (t/is (th/success? out))
          (t/is (= {:authorized true :reason :sso-satisfied} (:result out))))))))

(t/deftest nitrate-sso-required-error-resolves-the-team-from-the-file
  (t/testing "the workspace path, where the file id arrives as :id, still reports the team"
    (let [profile         (th/create-profile* 1 {:is-active true})
          file            (th/create-file* 1 {:profile-id (:id profile)
                                              :project-id (:default-project-id profile)})
          organization-id (uuid/random)]
      (with-redefs [nitrate/sso-session-authorized? (unauthorized-sso-mock organization-id)]
        (let [data (ex-data (sso-gate-error {::rpc/id-type :file}
                                            {::rpc/profile-id (:id profile)
                                             :id (:id file)}
                                            th/*system*))]
          (t/is (= :authentication (:type data)))
          (t/is (= :nitrate-sso-required (:code data)))
          (t/is (= organization-id (:organization-id data)))
          (t/is (= (:default-team-id profile) (:team-id data))))))))

(t/deftest nitrate-sso-required-error-keeps-the-team-known-by-the-request
  (t/testing "an explicit team-id is not dropped by an explicit organization-id"
    (let [profile-id      (uuid/random)
          team-id         (uuid/random)
          organization-id (uuid/random)]
      ;; The nitrate payload carries no organization-id here, so the one from
      ;; the request params is the only one left to report.
      (with-redefs [nitrate/sso-session-authorized? (unauthorized-sso-mock nil)]
        (let [data (ex-data (sso-gate-error {}
                                            {::rpc/profile-id profile-id
                                             :team-id team-id
                                             :organization-id organization-id}
                                            {}))]
          (t/is (= organization-id (:organization-id data)))
          (t/is (= team-id (:team-id data))))))))

(t/deftest nitrate-sso-required-error-resolves-the-team-with-a-known-organization
  (t/testing "knowing the organization does not stop the team lookup"
    (let [profile         (th/create-profile* 1 {:is-active true})
          file            (th/create-file* 1 {:profile-id (:id profile)
                                              :project-id (:default-project-id profile)})
          organization-id (uuid/random)]
      (with-redefs [nitrate/sso-session-authorized? (unauthorized-sso-mock nil)]
        (let [data (ex-data (sso-gate-error {}
                                            {::rpc/profile-id (:id profile)
                                             :organization-id organization-id
                                             :file-id (:id file)}
                                            th/*system*))]
          (t/is (= organization-id (:organization-id data)))
          (t/is (= (:default-team-id profile) (:team-id data))))))))

(t/deftest nitrate-sso-required-error-reaches-the-client-in-the-401-body
  (t/testing "the ids survive the http error response, not only the exception"
    (let [profile-id      (uuid/random)
          team-id         (uuid/random)
          organization-id (uuid/random)]
      (with-redefs [nitrate/sso-session-authorized? (unauthorized-sso-mock organization-id)]
        (let [cause    (sso-gate-error {}
                                       {::rpc/profile-id profile-id
                                        :team-id team-id}
                                       {})
              response (http-errors/handle cause {})
              body     (::yres/body response)]
          (t/is (= 401 (::yres/status response)))
          (t/is (= :nitrate-sso-required (:code body)))
          (t/is (= organization-id (:organization-id body)))
          (t/is (= team-id (:team-id body))))))))

(t/deftest leave-organization-happy-path-no-extra-teams
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})

        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})
        project          (th/create-project* 99 {:profile-id (:id profile-user)
                                                 :team-id (:id organization-default-team)})
        _                (th/create-file* 99 {:profile-id (:id profile-user)
                                              :project-id (:id project)})

        organization-id         (uuid/random)
        ;; The user's personal penpot team in the organization context
        your-penpot-id (:id organization-default-team)

        organization-summary          (make-organization-summary
                                       :organization-id   organization-id
                                       :organization-name "Test Org"
                                       :owner-id          (:id profile-owner)
                                       :your-penpot-teams [your-penpot-id]
                                       :organization-teams         [])
        remove-profile-params (atom nil)]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary remove-profile-params)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (nil? (:result out)))

        ;; The personal team must be renamed with the organization prefix and
        ;; unset as a default team.
        (let [team (th/db-get :team {:id your-penpot-id})]
          (t/is (str/starts-with? (:name team) "[Test Org] "))
          (t/is (false? (:is-default team))))

        (t/is (= (:id profile-user)
                 (:user-who-delete-member @remove-profile-params)))
        (t/is (= "organization-member"
                 (:deleted-by-role @remove-profile-params)))))))

(t/deftest leave-organization-deletes-organization-default-team-when-empty
  (let [profile-owner   (th/create-profile* 1 {:is-active true})
        profile-user    (th/create-profile* 2 {:is-active true})
        organization-default-team (th/create-team* 98 {:profile-id (:id profile-user)})

        organization-id          (uuid/random)
        your-penpot-id  (:id organization-default-team)

        organization-summary     (make-organization-summary
                                  :organization-id            organization-id
                                  :organization-name          "Test Org"
                                  :owner-id          (:id profile-owner)
                                  :your-penpot-teams [your-penpot-id]
                                  :organization-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (th/success? out))

        ;; Empty organization default team should be soft-deleted.
        (let [team (th/db-get :team {:id your-penpot-id} {::db/remove-deleted false})]
          (t/is (some? (:deleted-at team))))))))

(t/deftest leave-organization-keeps-and-renames-organization-default-team-when-has-files
  (let [profile-owner    (th/create-profile* 1 {:is-active true})
        profile-user     (th/create-profile* 2 {:is-active true})
        organization-default-team (th/create-team* 97 {:profile-id (:id profile-user)})
        project          (th/create-project* 97 {:profile-id (:id profile-user)
                                                 :team-id (:id organization-default-team)})
        _                (th/create-file* 97 {:profile-id (:id profile-user)
                                              :project-id (:id project)})

        organization-id           (uuid/random)
        your-penpot-id   (:id organization-default-team)

        organization-summary      (make-organization-summary
                                   :organization-id            organization-id
                                   :organization-name          "Test Org"
                                   :owner-id          (:id profile-owner)
                                   :your-penpot-teams [your-penpot-id]
                                   :organization-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (th/success? out))

        ;; Non-empty organization default team should remain and be renamed.
        (let [team (th/db-get :team {:id your-penpot-id})]
          (t/is (str/starts-with? (:name team) "[Test Org] "))
          (t/is (false? (:is-default team)))
          (t/is (nil? (:deleted-at team))))))))

(t/deftest leave-organization-with-teams-to-delete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user is the sole owner/member of team1
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete [(:id team1)]
                  :teams-to-leave  []}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))

        ;; team1 should be scheduled for deletion (deleted-at set)
        (let [team (th/db-get :team {:id (:id team1)} {::db/remove-deleted false})]
          (t/is (some? (:deleted-at team))))))))

(t/deftest leave-organization-with-ownership-transfer
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user owns team1; profile-owner is also a member
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1) :reassign-to (:id profile-owner)}]}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))

        ;; profile-user should no longer be a member of team1
        (let [rel (th/db-get :team-profile-rel
                             {:team-id    (:id team1)
                              :profile-id (:id profile-user)})]
          (t/is (nil? rel)))

        ;; profile-owner should have been promoted to owner
        (let [rel (th/db-get :team-profile-rel
                             {:team-id    (:id team1)
                              :profile-id (:id profile-owner)})]
          (t/is (true? (:is-owner rel))))))))

(t/deftest leave-organization-exit-as-non-owner
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-owner owns team1; profile-user is a non-owner member
        team1          (th/create-team* 1 {:profile-id (:id profile-owner)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-user)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1)}]}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))

        ;; profile-user should no longer be a member of team1
        (let [rel (th/db-get :team-profile-rel
                             {:team-id    (:id team1)
                              :profile-id (:id profile-user)})]
          (t/is (nil? rel)))

        ;; The team itself should still exist
        (let [team (th/db-get :team {:id (:id team1)})]
          (t/is (nil? (:deleted-at team))))))))

(t/deftest get-leave-organization-summary-counts-default-team-as-delete-when-empty
  (let [profile-owner    (th/create-profile* 1 {:is-active true})
        profile-user     (th/create-profile* 2 {:is-active true})
        organization-default-team (th/create-team* 97 {:profile-id (:id profile-user)})

        organization-id  (uuid/random)
        your-penpot-id   (:id organization-default-team)
        organization-summary      (make-organization-summary
                                   :organization-id organization-id
                                   :organization-name "Test Org"
                                   :owner-id (:id profile-owner)
                                   :your-penpot-teams [your-penpot-id]
                                   :organization-teams [])]

    (with-redefs [nitrate/call (nitrate-organization-summary-only-mock organization-summary)]
      (let [out (th/command! {::th/type :get-leave-organization-summary
                              ::rpc/profile-id (:id profile-user)
                              :id organization-id
                              :default-team-id your-penpot-id})]
        (t/is (th/success? out))
        (t/is (= {:teams-to-delete 0
                  :teams-to-transfer 0
                  :teams-to-exit 0
                  :teams-to-detach 0
                  :member-added-at (ct/inst "2026-07-17T12:00:00Z")
                  :organization-member-count-before 2}
                 (:result out)))))))

(t/deftest get-leave-organization-summary-counts-default-team-as-keep-when-has-files
  (let [profile-owner    (th/create-profile* 1 {:is-active true})
        profile-user     (th/create-profile* 2 {:is-active true})
        organization-default-team (th/create-team* 96 {:profile-id (:id profile-user)})
        project          (th/create-project* 96 {:profile-id (:id profile-user)
                                                 :team-id (:id organization-default-team)})
        _                (th/create-file* 96 {:profile-id (:id profile-user)
                                              :project-id (:id project)})
        extra-team       (th/create-team* 95 {:profile-id (:id profile-user)})

        organization-id  (uuid/random)
        your-penpot-id   (:id organization-default-team)
        organization-summary      (make-organization-summary
                                   :organization-id organization-id
                                   :organization-name "Test Org"
                                   :owner-id (:id profile-owner)
                                   :your-penpot-teams [your-penpot-id]
                                   :organization-teams [(:id extra-team)])]

    (with-redefs [nitrate/call (nitrate-organization-summary-only-mock organization-summary)]
      (let [out (th/command! {::th/type :get-leave-organization-summary
                              ::rpc/profile-id (:id profile-user)
                              :id organization-id
                              :default-team-id your-penpot-id})]
        (t/is (th/success? out))
        ;; extra-team is deletable, default team has files and is preserved.
        (t/is (= {:teams-to-delete 1
                  :teams-to-transfer 0
                  :teams-to-exit 0
                  :teams-to-detach 1
                  :member-added-at (ct/inst "2026-07-17T12:00:00Z")
                  :organization-member-count-before 2}
                 (:result out)))))))

(t/deftest leave-organization-error-organization-owner-cannot-leave
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-owner)})
        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        ;; profile-owner IS the organization owner in the organization-summary
        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-owner)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :organization-owner-cannot-leave (th/ex-code (:error out))))))))

(t/deftest leave-organization-error-invalid-default-team-id
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})
        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      ;; Pass a random UUID that is not in the your-penpot-teams list
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id (uuid/random)
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unit Tests for calculate-valid-teams
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private calculate-valid-teams
  (or (ns-resolve 'app.rpc.commands.nitrate 'calculate-valid-teams)
      (throw (ex-info "Unable to resolve calculate-valid-teams"
                      {:ns 'app.rpc.commands.nitrate
                       :symbol 'calculate-valid-teams}))))

(defn- make-team [id & {:keys [is-owner num-members member-ids]
                        :or   {is-owner false num-members 1 member-ids []}}]
  {:id id :is-owner is-owner :num-members num-members :member-ids member-ids})

(t/deftest calculate-valid-teams-no-organization-teams
  (let [default-id (uuid/random)
        default-team (make-team default-id)
        result (calculate-valid-teams [default-team] default-id)]
    (t/is (= default-team (:valid-default-team result)))
    (t/is (empty? (:valid-teams-to-delete-ids result)))
    (t/is (empty? (:valid-teams-to-transfer result)))
    (t/is (empty? (:valid-teams-to-exit result)))))

(t/deftest calculate-valid-teams-default-not-found
  (let [default-id   (uuid/random)
        other-id     (uuid/random)
        other-team   (make-team other-id)
        ;; default-id is not in organization-teams at all
        result (calculate-valid-teams [other-team] default-id)]
    (t/is (nil? (:valid-default-team result)))))

(t/deftest calculate-valid-teams-sole-owner-team
  (let [default-id (uuid/random)
        team-id    (uuid/random)
        default    (make-team default-id)
        solo-team  (make-team team-id :is-owner true :num-members 1)
        result     (calculate-valid-teams [default solo-team] default-id)]
    (t/is (contains? (:valid-teams-to-delete-ids result) team-id))
    (t/is (empty? (:valid-teams-to-transfer result)))
    (t/is (empty? (:valid-teams-to-exit result)))))

(t/deftest calculate-valid-teams-owned-multi-member-team
  (let [default-id (uuid/random)
        team-id    (uuid/random)
        default    (make-team default-id)
        ;; owner of a team with 3 members — must be transferred
        multi-team (make-team team-id :is-owner true :num-members 3)
        result     (calculate-valid-teams [default multi-team] default-id)]
    (t/is (empty? (:valid-teams-to-delete-ids result)))
    (t/is (= [team-id] (map :id (:valid-teams-to-transfer result))))
    (t/is (empty? (:valid-teams-to-exit result)))))

(t/deftest calculate-valid-teams-non-owner-multi-member-team
  (let [default-id (uuid/random)
        team-id    (uuid/random)
        default    (make-team default-id)
        ;; non-owner member of a team with 2 members — can just exit
        exit-team  (make-team team-id :is-owner false :num-members 2)
        result     (calculate-valid-teams [default exit-team] default-id)]
    (t/is (empty? (:valid-teams-to-delete-ids result)))
    (t/is (empty? (:valid-teams-to-transfer result)))
    (t/is (= [team-id] (map :id (:valid-teams-to-exit result))))))

(t/deftest calculate-valid-teams-mixed
  (let [default-id   (uuid/random)
        solo-id      (uuid/random)
        transfer-id  (uuid/random)
        exit-id      (uuid/random)
        default      (make-team default-id)
        solo-team    (make-team solo-id     :is-owner true  :num-members 1)
        transfer-team (make-team transfer-id :is-owner true  :num-members 2)
        exit-team    (make-team exit-id     :is-owner false :num-members 3)
        result       (calculate-valid-teams [default solo-team transfer-team exit-team] default-id)]
    (t/is (= #{solo-id} (:valid-teams-to-delete-ids result)))
    (t/is (= [transfer-id] (map :id (:valid-teams-to-transfer result))))
    (t/is (= [exit-id] (map :id (:valid-teams-to-exit result))))
    (t/is (= default-id (:id (:valid-default-team result))))))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration: combined delete + leave
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest leave-organization-combined-delete-and-leave
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; team1: profile-user is sole owner — must delete
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        ;; team2: profile-user owns it, profile-owner is also member — must transfer
        team2          (th/create-team* 2 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team2)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        ;; team3: profile-owner owns it, profile-user is non-owner member — can exit
        team3          (th/create-team* 3 {:profile-id (:id profile-owner)})
        _              (th/create-team-role* {:team-id    (:id team3)
                                              :profile-id (:id profile-user)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1) (:id team2) (:id team3)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete [(:id team1)]
                  :teams-to-leave  [{:id (:id team2) :reassign-to (:id profile-owner)}
                                    {:id (:id team3)}]}
            out  (th/command! data)]

        (t/is (th/success? out))

        ;; team1 should be soft-deleted
        (let [team (th/db-get :team {:id (:id team1)} {::db/remove-deleted false})]
          (t/is (some? (:deleted-at team))))

        ;; profile-user should no longer be a member of team2
        (let [rel (th/db-get :team-profile-rel {:team-id (:id team2) :profile-id (:id profile-user)})]
          (t/is (nil? rel)))

        ;; profile-owner should now own team2
        (let [rel (th/db-get :team-profile-rel {:team-id (:id team2) :profile-id (:id profile-owner)})]
          (t/is (true? (:is-owner rel))))

        ;; profile-user should no longer be a member of team3
        (let [rel (th/db-get :team-profile-rel {:team-id (:id team3) :profile-id (:id profile-user)})]
          (t/is (nil? rel)))

        ;; team3 itself should still exist (profile-owner is still there)
        (let [team (th/db-get :team {:id (:id team3)})]
          (t/is (some? team)))))))
(t/deftest leave-organization-error-teams-to-delete-incomplete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user is the sole owner/member of both team1 and team2
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        team2          (th/create-team* 2 {:profile-id (:id profile-user)})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1) (:id team2)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      ;; Only team1 is listed; team2 is also a sole-owner team and must be included
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete [(:id team1)]
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-organization-error-cannot-delete-multi-member-team
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; team1 has two members: profile-user (owner) and profile-owner (editor)
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      ;; team1 has 2 members so it is not a valid deletion candidate
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete [(:id team1)]
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-organization-error-teams-to-leave-incomplete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user owns team1, which also has profile-owner as editor
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      ;; team1 must be transferred (owner + multiple members) but is absent
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-organization-error-reassign-to-self
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      ;; reassign-to points to the profile that is leaving — not allowed
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1) :reassign-to (:id profile-user)}]}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-organization-error-reassign-to-non-member
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        profile-other  (th/create-profile* 3 {:is-active true})
        ;; team1 has profile-user (owner) and profile-owner (editor) — NOT profile-other
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      ;; profile-other is not a member of team1
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1) :reassign-to (:id profile-other)}]}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest all-team-members-in-organizations-returns-organization-id->boolean-map
  (let [profile-user  (th/create-profile* 201 {:is-active true})
        profile-other (th/create-profile* 202 {:is-active true})
        team          (th/create-team* 201 {:profile-id (:id profile-user)})
        _             (th/create-team-role* {:team-id (:id team)
                                             :profile-id (:id profile-other)
                                             :role :editor})
        team-member-ids (->> (th/db-query :team-profile-rel {:team-id (:id team)})
                             (map :profile-id)
                             (into #{}))
        organization-id-1      (uuid/random)
        organization-id-2      (uuid/random)
        calls         (atom [])]
    (with-redefs [cf/flags (conj cf/flags :admin-console)
                  nitrate/call (fn [_cfg method params]
                                 (swap! calls conj [method params])
                                 (case method
                                   :get-organization-membership {:is-member true
                                                                 :organization-id (:organization-id params)}
                                   :get-organization-members (get {organization-id-1 (vec team-member-ids)
                                                                   organization-id-2 [(:id profile-user)]}
                                                                  (:organization-id params)
                                                                  [])
                                   nil))]
      (let [out (th/command! {::th/type :all-team-members-in-organizations
                              ::rpc/profile-id (:id profile-user)
                              :team-id (:id team)
                              :organization-ids [organization-id-1 organization-id-2]})
            methods (map first @calls)
            membership-calls (count (filter #(= :get-organization-membership %) methods))
            get-members-calls (count (filter #(= :get-organization-members %) methods))]
        (t/is (th/success? out))
        (t/is (= {organization-id-1 true
                  organization-id-2 false}
                 (:result out)))
        (t/is (= 2 membership-calls))
        (t/is (= 2 get-members-calls))))))

(t/deftest all-team-members-in-organizations-fails-before-fetching-organization-members
  (let [profile-user  (th/create-profile* 203 {:is-active true})
        team          (th/create-team* 203 {:profile-id (:id profile-user)})
        organization-id-1      (uuid/random)
        organization-id-2      (uuid/random)
        calls         (atom [])]
    (with-redefs [cf/flags (conj cf/flags :admin-console)
                  nitrate/call (fn [_cfg method params]
                                 (swap! calls conj [method params])
                                 (case method
                                   :get-organization-membership (if (= (:organization-id params) organization-id-2)
                                                                  {:is-member false
                                                                   :organization-id (:organization-id params)}
                                                                  {:is-member true
                                                                   :organization-id (:organization-id params)})
                                   :get-organization-members []
                                   nil))]
      (let [out (th/command! {::th/type :all-team-members-in-organizations
                              ::rpc/profile-id (:id profile-user)
                              :team-id (:id team)
                              :organization-ids [organization-id-1 organization-id-2]})
            methods (map first @calls)]
        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :user-doesnt-belong-organization (th/ex-code (:error out))))
        (t/is (= 0 (count (filter #(= :get-organization-members %) methods))))))))

(t/deftest leave-organization-error-reassign-on-non-owned-team
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-owner owns team1; profile-user is just a non-owner member
        team1          (th/create-team* 1 {:profile-id (:id profile-owner)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-user)
                                              :role       :editor})
        organization-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id organization-default-team)

        organization-summary    (make-organization-summary
                                 :organization-id            organization-id
                                 :organization-name          "Test Org"
                                 :owner-id          (:id profile-owner)
                                 :your-penpot-teams [your-penpot-id]
                                 :organization-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock organization-summary)]
      ;; profile-user is not the owner so providing reassign-to is invalid
      (let [data {::th/type        :leave-organization
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1) :reassign-to (:id profile-owner)}]}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(defn- add-team-to-organization-nitrate-mock
  [{:keys [organization-id organization-summary organization-perms owner-id team-id sso-active? set-team-params]}]
  (fn [_cfg method params]
    (case method
      :get-organization-membership (if (= (:profile-id params) owner-id)
                                     {:is-member true :organization-id organization-id}
                                     {:is-member false :organization-id organization-id})
      :get-organization-members [owner-id]
      :get-team-organization {:organization nil}
      :get-organization-permissions organization-perms
      :set-team-organization (do
                               (when set-team-params
                                 (reset! set-team-params params))
                               {:id team-id})
      :get-organization-sso {:active sso-active?}
      :get-organization-summary (assoc organization-summary :teams [{:id team-id}])
      :add-profile-to-organization {:is-member true}
      nil)))

(t/deftest add-team-to-organization-sends-sso-emails-to-new-members-and-invitees
  (let [owner      (th/create-profile* 301 {:is-active true
                                            :fullname "Owner"
                                            :email "owner301@example.com"})
        member     (th/create-profile* 302 {:is-active true
                                            :fullname "Member"
                                            :email "member302@example.com"})
        team       (th/create-team* 301 {:profile-id (:id owner)})
        _          (th/create-team-role* {:team-id (:id team)
                                          :profile-id (:id member)
                                          :role :editor})
        organization-id     (uuid/random)
        organization-name   "SSO Org"
        organization-summary {:id organization-id
                              :name organization-name
                              :owner-id (:id owner)
                              :teams []}
        organization-perms  {:owner-id (:id owner)
                             :permissions {:create-teams "any"
                                           :move-teams "always"
                                           :new-team-members "members"}}
        sent       (atom [])
        set-team-params (atom nil)]

    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id team)
                    :org-id nil
                    :email-to "external301@example.com"
                    :created-by (:id owner)
                    :role "editor"
                    :valid-until (ct/in-future "48h")})

    (with-redefs [cf/flags (conj cf/flags :admin-console)
                  nitrate/call (add-team-to-organization-nitrate-mock
                                {:organization-id organization-id
                                 :organization-summary organization-summary
                                 :organization-perms organization-perms
                                 :owner-id (:id owner)
                                 :team-id (:id team)
                                 :sso-active? true
                                 :set-team-params set-team-params})
                  teams/initialize-user-in-organization (fn [& _] nil)
                  eml/send! (fn [params] (swap! sent conj params))]
      (let [out (th/command! {::th/type :add-team-to-organization
                              ::rpc/profile-id (:id owner)
                              :team-id (:id team)
                              :organization-id organization-id})]
        (t/is (th/success? out))))

    (t/is (= {:team-id (:id team)
              :organization-id organization-id
              :is-default false}
             @set-team-params))

    (let [emails (->> @sent (map :to) set)]
      (t/is (= 2 (count @sent)))
      (t/is (= #{"member302@example.com" "external301@example.com"} emails))
      (doseq [email-params @sent]
        (t/is (= organization-name (:organization-name email-params)))
        (t/is (= eml/organization-setup-sso (::eml/factory email-params)))))))

(t/deftest create-team-in-organization-passes-association-to-nitrate
  (let [organization-id (uuid/random)
        team            {:id (uuid/random)
                         :created-at (ct/now)}
        params*         (atom nil)]
    (with-redefs [nitrate/call (fn [_cfg method params]
                                 (when (= method :set-team-organization)
                                   (reset! params* params))
                                 {:id (:id team)})]
      (nitrate/set-team-organization
       {}
       team
       {:organization-id organization-id
        :is-default false}))

    (t/is (= {:team-id (:id team)
              :organization-id organization-id
              :is-default false}
             @params*))))

(t/deftest add-team-to-organization-skips-sso-emails-when-sso-inactive
  (let [owner      (th/create-profile* 303 {:is-active true :email "owner303@example.com"})
        member     (th/create-profile* 304 {:is-active true :email "member304@example.com"})
        team       (th/create-team* 303 {:profile-id (:id owner)})
        _          (th/create-team-role* {:team-id (:id team)
                                          :profile-id (:id member)
                                          :role :editor})
        organization-id     (uuid/random)
        organization-summary {:id organization-id
                              :name "No SSO Org"
                              :owner-id (:id owner)
                              :teams []}
        organization-perms  {:owner-id (:id owner)
                             :permissions {:create-teams "any"
                                           :move-teams "always"
                                           :new-team-members "members"}}
        sent       (atom [])]

    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id team)
                    :org-id nil
                    :email-to "external303@example.com"
                    :created-by (:id owner)
                    :role "editor"
                    :valid-until (ct/in-future "48h")})

    (with-redefs [cf/flags (conj cf/flags :admin-console)
                  nitrate/call (add-team-to-organization-nitrate-mock
                                {:organization-id organization-id
                                 :organization-summary organization-summary
                                 :organization-perms organization-perms
                                 :owner-id (:id owner)
                                 :team-id (:id team)
                                 :sso-active? false})
                  teams/initialize-user-in-organization (fn [& _] nil)
                  eml/send! (fn [params] (swap! sent conj params))]
      (let [out (th/command! {::th/type :add-team-to-organization
                              ::rpc/profile-id (:id owner)
                              :team-id (:id team)
                              :organization-id organization-id})]
        (t/is (th/success? out))
        (t/is (empty? @sent))))))

(t/deftest get-nitrate-activation-code-request
  (let [profile    (th/create-profile* 1 {:is-active true})
        nitrate-id "nitrate-instance-1"
        public-key "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----"
        now        (ct/now)]
    (with-redefs [cf/flags     (conj cf/flags :admin-console)
                  ct/now       (constantly now)
                  nitrate/call (fn [_cfg method _params]
                                 (t/is (= :get-identity method))
                                 {:nitrate-id nitrate-id
                                  :public-key public-key})]
      (let [out  (th/command! {::th/type :get-nitrate-activation-code-request
                               ::rpc/profile-id (:id profile)})
            body (-> (:result out)
                     (bc/b64->str)
                     (json/decode :key-fn json/read-kebab-key))]
        (t/is (th/success? out))
        (t/is (= {:nitrate-id nitrate-id
                  :public-key public-key
                  :email      (:email profile)
                  :iat        (ct/seconds now)}
                 body))

        (let [[_ method-fn] (get-in th/*system* [:app.rpc/methods :get-nitrate-activation-code-request])
              result        (method-fn {::rpc/profile-id (:id profile)
                                        ::rpc/request-at now})
              headers       (::http/headers (meta result))]
          (t/is (rph/wrapped? result))
          (t/is (= "text/plain" (get headers "content-type")))
          (t/is (= "attachment; filename=\"penpot-activation-code-request.txt\""
                   (get headers "content-disposition"))))))))

(t/deftest get-nitrate-activation-code-request-identity-unavailable
  (let [profile (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags     (conj cf/flags :admin-console)
                  nitrate/call (fn [_cfg _method _params] nil)]
      (let [out (th/command! {::th/type :get-nitrate-activation-code-request
                              ::rpc/profile-id (:id profile)})]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-code? (:error out) :nitrate-identity-unavailable))))))

(t/deftest redeem-nitrate-activation-code-used
  (let [profile (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags     (conj cf/flags :admin-console)
                  nitrate/call (fn [_cfg method _params]
                                 (t/is (= :redeem-activation-code method))
                                 (ex/raise :type :nitrate-http-error
                                           :status 409
                                           :hint "activation code already used"))]
      (let [out (th/command! {::th/type :redeem-nitrate-activation-code
                              ::rpc/profile-id (:id profile)
                              :activation-code "already-used-code"})]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-code? (:error out) :used-activation-code))))))

(t/deftest redeem-nitrate-activation-code-expired
  (let [profile (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags     (conj cf/flags :admin-console)
                  nitrate/call (fn [_cfg method _params]
                                 (t/is (= :redeem-activation-code method))
                                 (ex/raise :type :nitrate-http-error
                                           :status 410
                                           :hint "activation code expired"))]
      (let [out (th/command! {::th/type :redeem-nitrate-activation-code
                              ::rpc/profile-id (:id profile)
                              :activation-code "expired-code"})]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-code? (:error out) :expired-activation-code))))))

(t/deftest redeem-nitrate-activation-code-invalid
  (let [profile (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags     (conj cf/flags :admin-console)
                  nitrate/call (fn [_cfg method _params]
                                 (t/is (= :redeem-activation-code method))
                                 (ex/raise :type :nitrate-http-error
                                           :status 422
                                           :hint "invalid activation code"))]
      (let [out (th/command! {::th/type :redeem-nitrate-activation-code
                              ::rpc/profile-id (:id profile)
                              :activation-code "invalid-code"})]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-code? (:error out) :invalid-activation-code))))))
