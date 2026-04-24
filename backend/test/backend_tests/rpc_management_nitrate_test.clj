;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-management-nitrate-test
  (:require
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.email :as email]
   [app.msgbus :as mbus]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.set :as set]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- management-command-with-nitrate!
  [data]
  (th/management-command! data [:nitrate]))

(t/deftest authenticate-success
  (let [profile (th/create-profile* 1 {:is-active true
                                       :fullname "Nitrate User"})
        out     (management-command-with-nitrate! {::th/type :authenticate
                                                   ::rpc/profile-id (:id profile)})]
    (t/is (th/success? out))
    (t/is (= (:id profile) (-> out :result :id)))
    (t/is (= "Nitrate User" (-> out :result :name)))
    (t/is (= (:email profile) (-> out :result :email)))
    (t/is (nil? (-> out :result :photo-url)))))

(t/deftest authenticate-requires-authentication
  (let [out (management-command-with-nitrate! {::th/type :authenticate})]
    (t/is (not (th/success? out)))
    (t/is (= :authentication (th/ex-type (:error out))))
    (t/is (= :authentication-required (th/ex-code (:error out))))))

(t/deftest get-penpot-version
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (management-command-with-nitrate! {::th/type :get-penpot-version
                                                   ::rpc/profile-id (:id profile)})]
    (t/is (th/success? out))
    (t/is (= cf/version (-> out :result :version)))))

(t/deftest get-teams-returns-only-owned-non-default-non-deleted
  (let [profile      (th/create-profile* 1 {:is-active true})
        other        (th/create-profile* 2 {:is-active true})
        owned-team   (th/create-team* 1 {:profile-id (:id profile)})
        deleted-team (th/create-team* 2 {:profile-id (:id profile)})
        _            (th/db-update! :team
                                    {:deleted-at (ct/now)}
                                    {:id (:id deleted-team)})
        other-team   (th/create-team* 3 {:profile-id (:id other)})
        _            (th/create-team-role* {:team-id (:id other-team)
                                            :profile-id (:id profile)
                                            :role :editor})
        out          (management-command-with-nitrate! {::th/type :get-teams
                                                        ::rpc/profile-id (:id profile)})]
    (t/is (th/success? out))
    (t/is (= #{(:id owned-team)}
             (->> out :result (map :id) set)))
    (t/is (= #{(:name owned-team)}
             (->> out :result (map :name) set)))))

(t/deftest notify-team-change-publishes-event
  (let [team-id          (uuid/random)
        organization-id  (uuid/random)
        organization     {:id organization-id
                          :name "Acme Inc"
                          :slug "acme-inc"
                          :owner-id (uuid/random)
                          :avatar-bg-url "http://example.com/avatar.svg"}
        calls            (atom [])
        out              (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                                   (swap! calls conj {:topic topic
                                                                      :message message}))]
                           (management-command-with-nitrate! {::th/type :notify-team-change
                                                              :id team-id
                                                              :is-your-penpot false
                                                              :organization organization}))]
    (t/is (th/success? out))
    (t/is (= 1 (count @calls)))
    (t/is (= uuid/zero (-> @calls first :topic)))
    (let [msg (-> @calls first :message)]
      (t/is (= :team-org-change (:type msg)))
      (t/is (= nil (:notification msg)))
      (t/is (= team-id (-> msg :team :id)))
      (t/is (= false (-> msg :team :is-your-penpot)))
      (t/is (= (:id organization) (-> msg :team :organization :id)))
      (t/is (= (:name organization) (-> msg :team :organization :name)))
      (t/is (= (:slug organization) (-> msg :team :organization :slug)))
      (t/is (= (:owner-id organization) (-> msg :team :organization :owner-id)))
      (t/is (= (:avatar-bg-url organization) (str (-> msg :team :organization :avatar-bg-url)))))))

(t/deftest notify-user-added-to-organization-creates-default-org-team
  (let [profile      (th/create-profile* 1 {:is-active true})
        before-teams (->> (th/db-query :team-profile-rel {:profile-id (:id profile)
                                                          :is-owner true})
                          (map :team-id)
                          set)
        out          (management-command-with-nitrate! {::th/type :notify-user-added-to-organization
                                                        :profile-id (:id profile)
                                                        :organization-id (uuid/random)
                                                        :role "owner"})
        after-teams  (->> (th/db-query :team-profile-rel {:profile-id (:id profile)
                                                          :is-owner true})
                          (map :team-id)
                          set)
        new-team-id  (first (set/difference after-teams before-teams))
        new-team     (th/db-get :team {:id new-team-id})]
    (t/is (th/success? out))
    (t/is (= 1 (count (set/difference after-teams before-teams))))
    (t/is (= "Your Penpot" (:name new-team)))
    (t/is (true? (:is-default new-team)))))

(t/deftest get-managed-profiles-returns-unique-members-for-owned-teams
  (let [owner     (th/create-profile* 1 {:is-active true})
        member1   (th/create-profile* 2 {:is-active true})
        member2   (th/create-profile* 3 {:is-active true})
        team1     (th/create-team* 1 {:profile-id (:id owner)})
        team2     (th/create-team* 2 {:profile-id (:id owner)})
        _         (th/create-team-role* {:team-id (:id team1)
                                         :profile-id (:id member1)
                                         :role :editor})
        _         (th/create-team-role* {:team-id (:id team1)
                                         :profile-id (:id member2)
                                         :role :editor})
        _         (th/create-team-role* {:team-id (:id team2)
                                         :profile-id (:id member1)
                                         :role :editor})
        out       (management-command-with-nitrate! {::th/type :get-managed-profiles
                                                     ::rpc/profile-id (:id owner)})]
    (t/is (th/success? out))
    (t/is (= #{(:id member1) (:id member2)}
             (->> out :result (map :id) set)))
    (t/is (= #{(:email member1) (:email member2)}
             (->> out :result (map :email) set)))))

(t/deftest get-teams-summary-returns-teams-and-files-count
  (let [profile (th/create-profile* 1 {:is-active true})
        team1   (th/create-team* 1 {:profile-id (:id profile)})
        team2   (th/create-team* 2 {:profile-id (:id profile)})
        proj1   (th/create-project* 1 {:profile-id (:id profile)
                                       :team-id (:id team1)})
        proj2   (th/create-project* 2 {:profile-id (:id profile)
                                       :team-id (:id team2)})
        _       (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id proj1)})
        _       (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id proj2)})
        out     (management-command-with-nitrate! {::th/type :get-teams-summary
                                                   ::rpc/profile-id (:id profile)
                                                   :ids [(:id team1) (:id team2)]})]
    (t/is (th/success? out))
    (t/is (= 2 (-> out :result :num-files)))
    (t/is (= #{(:id team1) (:id team2)}
             (->> out :result :teams (map :id) set)))))

(t/deftest notify-org-deletion-prefixes-teams-and-notifies
  (let [profile        (th/create-profile* 1 {:is-active true})
        extra-team     (th/create-team* 1 {:profile-id (:id profile)})
        default-team   (th/db-get :team {:id (:default-team-id profile)})
        teams          [(:id default-team) (:id extra-team)]
        organization-name       "Acme / Design"
        expected-start (str "[" (d/sanitize-string organization-name) "] ")
        calls          (atom [])
        out            (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                                 (swap! calls conj {:topic topic
                                                                    :message message}))]
                         (management-command-with-nitrate! {::th/type :notify-org-deletion
                                                            ::rpc/profile-id (:id profile)
                                                            :teams teams
                                                            :organization-name organization-name}))
        updated        (map #(th/db-get :team {:id %} {::db/remove-deleted false}) teams)]
    (t/is (th/success? out))
    (t/is (= 2 (count @calls)))
    (doseq [team updated]
      (t/is (false? (:is-default team)))
      (t/is (str/starts-with? (:name team) expected-start)))
    (doseq [call @calls]
      (t/is (= uuid/zero (:topic call)))
      (t/is (= :team-org-change (-> call :message :type)))
      (t/is (= organization-name (-> call :message :team :organization :name)))
      (t/is (= "dashboard.org-deleted" (-> call :message :notification))))))

(t/deftest get-profile-by-email-success-and-not-found
  (let [profile (th/create-profile* 1 {:is-active true
                                       :fullname "Lookup by Email"})
        ok-out  (management-command-with-nitrate! {::th/type :get-profile-by-email
                                                   ::rpc/profile-id (:id profile)
                                                   :email (:email profile)})
        ko-out  (management-command-with-nitrate! {::th/type :get-profile-by-email
                                                   ::rpc/profile-id (:id profile)
                                                   :email "not-found@example.com"})]
    (t/is (th/success? ok-out))
    (t/is (= (:id profile) (-> ok-out :result :id)))
    (t/is (= "Lookup by Email" (-> ok-out :result :name)))
    (t/is (nil? (-> ok-out :result :photo-url)))

    (t/is (not (th/success? ko-out)))
    (t/is (= :not-found (th/ex-type (:error ko-out))))
    (t/is (= :profile-not-found (th/ex-code (:error ko-out))))))

(t/deftest get-profile-by-id-success-and-not-found
  (let [profile (th/create-profile* 1 {:is-active true
                                       :fullname "Lookup by Id"})
        ok-out  (management-command-with-nitrate! {::th/type :get-profile-by-id
                                                   ::rpc/profile-id (:id profile)
                                                   :id (:id profile)})
        ko-out  (management-command-with-nitrate! {::th/type :get-profile-by-id
                                                   ::rpc/profile-id (:id profile)
                                                   :id (uuid/random)})]
    (t/is (th/success? ok-out))
    (t/is (= (:id profile) (-> ok-out :result :id)))
    (t/is (= "Lookup by Id" (-> ok-out :result :name)))
    (t/is (nil? (-> ok-out :result :photo-url)))

    (t/is (not (th/success? ko-out)))
    (t/is (= :not-found (th/ex-type (:error ko-out))))
    (t/is (= :profile-not-found (th/ex-code (:error ko-out))))))

(t/deftest get-org-invitations-returns-valid-deduped-by-email
  (let [profile      (th/create-profile* 1 {:is-active true})
        team-1       (th/create-team* 1 {:profile-id (:id profile)})
        team-2       (th/create-team* 2 {:profile-id (:id profile)})
        org-id       (uuid/random)
        org-summary  {:id org-id
                      :teams [{:id (:id team-1)}
                              {:id (:id team-2)}]}
        params       {::th/type :get-org-invitations
                      ::rpc/profile-id (:id profile)
                      :organization-id org-id}]

    ;; Same email appears in org and team invitations; only one should be returned.
    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :org-id org-id
                    :team-id nil
                    :email-to "dup@example.com"
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-future "24h")})

    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id team-1)
                    :org-id nil
                    :email-to "dup@example.com"
                    :created-by (:id profile)
                    :role "admin"
                    :valid-until (ct/in-future "72h")})

    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id team-2)
                    :org-id nil
                    :email-to "valid@example.com"
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-future "48h")})

    ;; Expired invitation should be ignored.
    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :org-id org-id
                    :team-id nil
                    :email-to "expired@example.com"
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-past "1h")})

    (let [out (with-redefs [nitrate/call (fn [_cfg method _params]
                                           (case method
                                             :get-org-summary org-summary
                                             nil))]
                (management-command-with-nitrate! params))
          result (:result out)
          emails (->> result (map :email) set)
          dedup  (->> result
                      (filter #(= "dup@example.com" (:email %)))
                      first)]
      (t/is (th/success? out))
      (t/is (= #{"dup@example.com" "valid@example.com"} emails))
      (t/is (= 2 (count result)))
      (t/is (some? (:id dedup)))
      (t/is (some? (:sent-at dedup)))
      (t/is (nil? (:organization-id dedup)))
      (t/is (nil? (:team-id dedup)))
      (t/is (nil? (:role dedup)))
      (t/is (nil? (:valid-until dedup))))))

(t/deftest get-org-invitations-includes-org-level-invitations-when-no-teams
  (let [profile      (th/create-profile* 1 {:is-active true})
        org-id       (uuid/random)
        org-summary  {:id org-id
                      :teams []}
        params       {::th/type :get-org-invitations
                      ::rpc/profile-id (:id profile)
                      :organization-id org-id}]

    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :org-id org-id
                    :team-id nil
                    :email-to "org-only@example.com"
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-future "24h")})

    (let [out (with-redefs [nitrate/call (fn [_cfg method _params]
                                           (case method
                                             :get-org-summary org-summary
                                             nil))]
                (management-command-with-nitrate! params))
          result (:result out)]
      (t/is (th/success? out))
      (t/is (= 1 (count result)))
      (t/is (= "org-only@example.com" (-> result first :email)))
      (t/is (some? (-> result first :sent-at))))))

(t/deftest get-org-invitations-returns-existing-profile-data
  (let [profile      (th/create-profile* 1 {:is-active true})
        invited      (th/create-profile* 2 {:is-active true
                                            :fullname "Invited User"})
        photo-id     (uuid/random)
        _            (th/db-insert! :storage-object {:id photo-id
                                                     :backend "assets-fs"})
        _            (th/db-update! :profile {:photo-id photo-id} {:id (:id invited)})
        org-id       (uuid/random)
        org-summary  {:id org-id
                      :teams []}
        params       {::th/type :get-org-invitations
                      ::rpc/profile-id (:id profile)
                      :organization-id org-id}]

    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :org-id org-id
                    :team-id nil
                    :email-to (:email invited)
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-future "24h")})

    (let [out (with-redefs [nitrate/call (fn [_cfg method _params]
                                           (case method
                                             :get-org-summary org-summary
                                             nil))]
                (management-command-with-nitrate! params))
          invitation (-> out :result first)]
      (t/is (th/success? out))
      (t/is (= "Invited User" (:name invitation)))
      (t/is (some? (:sent-at invitation)))
      (t/is (str/ends-with? (:photo-url invitation)
                            (str "/assets/by-id/" photo-id))))))

(t/deftest delete-org-invitations-removes-org-and-org-team-invitations-for-email
  (let [profile      (th/create-profile* 1 {:is-active true})
        team-1       (th/create-team* 1 {:profile-id (:id profile)})
        team-2       (th/create-team* 2 {:profile-id (:id profile)})
        outside-team (th/create-team* 3 {:profile-id (:id profile)})
        org-id       (uuid/random)
        org-summary  {:id org-id
                      :teams [{:id (:id team-1)}
                              {:id (:id team-2)}]}
        target-email "target@example.com"
        params       {::th/type :delete-org-invitations
                      ::rpc/profile-id (:id profile)
                      :organization-id org-id
                      :email "TARGET@example.com"}]

    ;; Should be deleted: org-level invitation for same org+email.
    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :org-id org-id
                    :team-id nil
                    :email-to target-email
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-future "24h")})

    ;; Should be deleted: team-level invitation for teams belonging to org summary.
    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id team-1)
                    :org-id nil
                    :email-to target-email
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-past "1h")})

    ;; Should remain: different email.
    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id team-2)
                    :org-id nil
                    :email-to "other@example.com"
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-future "24h")})

    ;; Should remain: same email but outside org scope.
    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id outside-team)
                    :org-id nil
                    :email-to target-email
                    :created-by (:id profile)
                    :role "editor"
                    :valid-until (ct/in-future "24h")})

    (let [out (with-redefs [nitrate/call (fn [_cfg method _params]
                                           (case method
                                             :get-org-summary org-summary
                                             nil))]
                (management-command-with-nitrate! params))
          remaining-target (th/db-query :team-invitation {:email-to target-email})
          remaining-other  (th/db-query :team-invitation {:email-to "other@example.com"})]
      (t/is (th/success? out))
      (t/is (nil? (:result out)))
      (t/is (= 1 (count remaining-target)))
      (t/is (= (:id outside-team) (:team-id (first remaining-target))))
      (t/is (= 1 (count remaining-other))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests: remove-from-org
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-org-summary
  [& {:keys [organization-id organization-name owner-id your-penpot-teams org-teams]
      :or   {your-penpot-teams [] org-teams []}}]
  {:id       organization-id
   :name     organization-name
   :owner-id owner-id
   :teams    (into
              (mapv (fn [id] {:id id :is-your-penpot true}) your-penpot-teams)
              (mapv (fn [id] {:id id :is-your-penpot false}) org-teams))})

(defn- nitrate-call-mock
  [org-summary]
  (fn [_cfg method _params]
    (case method
      :get-org-summary org-summary
      :get-org-membership {:organization-id (:id org-summary)
                           :is-member true}
      :remove-profile-from-org nil
      nil)))

(t/deftest remove-from-org-happy-path-no-extra-teams
  ;; User is only in its default team (which has files); it should be
  ;; kept, renamed and unset as default.  A notification must be sent.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        org-team    (th/create-team* 1 {:profile-id (:id user)})
        project     (th/create-project* 1 {:profile-id (:id user)
                                           :team-id   (:id org-team)})
        _           (th/create-file* 1 {:profile-id (:id user)
                                        :project-id (:id project)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [])
        calls       (atom [])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)
                                  mbus/pub! (fn [_bus & {:keys [topic message]}]
                                              (swap! calls conj {:topic topic :message message}))]
                      (management-command-with-nitrate!
                       {::th/type        :remove-from-org
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :organization-name        "Acme Org"
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    (t/is (nil? (:result out)))

    ;; default team preserved, renamed and unset as default
    (let [team (th/db-get :team {:id (:id org-team)})]
      (t/is (false? (:is-default team)))
      (t/is (str/starts-with? (:name team) "[Acme Org] ")))

    ;; exactly one notification sent to the user
    (t/is (= 1 (count @calls)))
    (let [msg (-> @calls first :message)]
      (t/is (= :user-org-change (:type msg)))
      (t/is (= (:id user) (:topic msg)))
      (t/is (= organization-id (:organization-id msg)))
      (t/is (= "Acme Org" (:organization-name msg)))
      (t/is (= "dashboard.user-no-longer-belong-org" (:notification msg))))))

(t/deftest remove-from-org-deletes-empty-default-team
  ;; When the default team has no files it should be soft-deleted.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        org-team    (th/create-team* 2 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)
                                  mbus/pub! (fn [& _] nil)]
                      (management-command-with-nitrate!
                       {::th/type        :remove-from-org
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :organization-name        "Acme Org"
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    (let [team (th/db-get :team {:id (:id org-team)} {::db/remove-deleted false})]
      (t/is (some? (:deleted-at team))))))

(t/deftest remove-from-org-deletes-sole-owner-team
  ;; When the user is the sole member of an org team it should be deleted.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        extra-team  (th/create-team* 3 {:profile-id (:id user)})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)
                                  mbus/pub! (fn [& _] nil)]
                      (management-command-with-nitrate!
                       {::th/type        :remove-from-org
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :organization-name        "Acme Org"
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    (let [team (th/db-get :team {:id (:id extra-team)} {::db/remove-deleted false})]
      (t/is (some? (:deleted-at team))))))

(t/deftest remove-from-org-transfers-ownership-of-multi-member-team
  ;; When the user owns a team that has another non-owner member, ownership
  ;; is transferred to that member by the endpoint automatically.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        candidate   (th/create-profile* 3 {:is-active true})
        extra-team  (th/create-team* 4 {:profile-id (:id user)})
        _           (th/create-team-role* {:team-id    (:id extra-team)
                                           :profile-id (:id candidate)
                                           :role       :editor})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)
                                  mbus/pub! (fn [& _] nil)]
                      (management-command-with-nitrate!
                       {::th/type        :remove-from-org
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :organization-name        "Acme Org"
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    ;; user no longer in extra-team
    (let [rel (th/db-get :team-profile-rel {:team-id (:id extra-team) :profile-id (:id user)})]
      (t/is (nil? rel)))
    ;; candidate promoted to owner
    (let [rel (th/db-get :team-profile-rel {:team-id (:id extra-team) :profile-id (:id candidate)})]
      (t/is (true? (:is-owner rel))))))

(t/deftest remove-from-org-exits-non-owned-team
  ;; When the user is a non-owner member of an org team, they simply leave.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        extra-team  (th/create-team* 5 {:profile-id (:id org-owner)})
        _           (th/create-team-role* {:team-id    (:id extra-team)
                                           :profile-id (:id user)
                                           :role       :editor})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)
                                  mbus/pub! (fn [& _] nil)]
                      (management-command-with-nitrate!
                       {::th/type        :remove-from-org
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :organization-name        "Acme Org"
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    ;; user no longer a member of extra-team
    (let [rel (th/db-get :team-profile-rel {:team-id (:id extra-team) :profile-id (:id user)})]
      (t/is (nil? rel)))
    ;; team still exists for the owner
    (let [team (th/db-get :team {:id (:id extra-team)})]
      (t/is (some? team)))))

(t/deftest remove-from-org-error-nobody-to-reassign
  ;; When the user owns a multi-member team but every other member is
  ;; also an owner, the auto-selection query finds nobody and raises.
  (let [other-owner (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        extra-team  (th/create-team* 6 {:profile-id (:id user)})
        ;; add other-owner to the team and make them co-owner directly in DB
        _           (th/create-team-role* {:team-id    (:id extra-team)
                                           :profile-id (:id other-owner)
                                           :role       :editor})
        _           (th/db-update! :team-profile-rel
                                   {:is-owner true :is-admin false}
                                   {:team-id (:id extra-team) :profile-id (:id other-owner)})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id other-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)
                                  mbus/pub! (fn [& _] nil)]
                      (management-command-with-nitrate!
                       {::th/type        :remove-from-org
                        ::rpc/profile-id (:id other-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :organization-name        "Acme Org"
                        :default-team-id (:id org-team)}))]
    (t/is (not (th/success? out)))
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= :nobody-to-reassign-team (th/ex-code (:error out))))))

;; Tests: get-remove-from-org-summary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest get-remove-from-org-summary-no-extra-teams
  ;; User only has a default team — nothing to delete/transfer/exit.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        org-team    (th/create-team* 1 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
                      (management-command-with-nitrate!
                       {::th/type        :get-remove-from-org-summary
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    (t/is (= {:teams-to-delete   0
              :teams-to-transfer 0
              :teams-to-exit     0}
             (:result out)))))

(t/deftest get-remove-from-org-summary-with-teams-to-delete
  ;; User owns a sole-member extra org team → 1 to delete.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        extra-team  (th/create-team* 3 {:profile-id (:id user)})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
                      (management-command-with-nitrate!
                       {::th/type        :get-remove-from-org-summary
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    (t/is (= {:teams-to-delete   1
              :teams-to-transfer 0
              :teams-to-exit     0}
             (:result out)))))

(t/deftest get-remove-from-org-summary-with-teams-to-transfer
  ;; User owns a multi-member extra org team → 1 to transfer.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        candidate   (th/create-profile* 3 {:is-active true})
        extra-team  (th/create-team* 4 {:profile-id (:id user)})
        _           (th/create-team-role* {:team-id    (:id extra-team)
                                           :profile-id (:id candidate)
                                           :role       :editor})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
                      (management-command-with-nitrate!
                       {::th/type        :get-remove-from-org-summary
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    (t/is (= {:teams-to-delete   0
              :teams-to-transfer 1
              :teams-to-exit     0}
             (:result out)))))

(t/deftest get-remove-from-org-summary-with-teams-to-exit
  ;; User is a non-owner member of an org team → 1 to exit.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        extra-team  (th/create-team* 5 {:profile-id (:id org-owner)})
        _           (th/create-team-role* {:team-id    (:id extra-team)
                                           :profile-id (:id user)
                                           :role       :editor})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        out         (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
                      (management-command-with-nitrate!
                       {::th/type        :get-remove-from-org-summary
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :default-team-id (:id org-team)}))]
    (t/is (th/success? out))
    (t/is (= {:teams-to-delete   0
              :teams-to-transfer 0
              :teams-to-exit     1}
             (:result out)))))

(t/deftest get-remove-from-org-summary-does-not-mutate
  ;; Calling the summary endpoint must not modify any teams.
  (let [org-owner   (th/create-profile* 1 {:is-active true})
        user        (th/create-profile* 2 {:is-active true})
        extra-team  (th/create-team* 6 {:profile-id (:id user)})
        org-team    (th/create-team* 99 {:profile-id (:id user)})
        organization-id      (uuid/random)
        org-summary (make-org-summary
                     :organization-id            organization-id
                     :organization-name          "Acme Org"
                     :owner-id          (:id org-owner)
                     :your-penpot-teams [(:id org-team)]
                     :org-teams         [(:id extra-team)])
        _           (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
                      (management-command-with-nitrate!
                       {::th/type        :get-remove-from-org-summary
                        ::rpc/profile-id (:id org-owner)
                        :profile-id      (:id user)
                        :organization-id          organization-id
                        :default-team-id (:id org-team)}))]
    ;; Both teams must still exist and be undeleted
    (let [t1 (th/db-get :team {:id (:id org-team)})]
      (t/is (some? t1))
      (t/is (nil? (:deleted-at t1))))
    (let [t2 (th/db-get :team {:id (:id extra-team)})]
      (t/is (some? t2))
      (t/is (nil? (:deleted-at t2))))
    ;; User must still be a member of both teams
    (let [rel1 (th/db-get :team-profile-rel {:team-id (:id org-team) :profile-id (:id user)})]
      (t/is (some? rel1)))
    (let [rel2 (th/db-get :team-profile-rel {:team-id (:id extra-team) :profile-id (:id user)})]
      (t/is (some? rel2)))))
