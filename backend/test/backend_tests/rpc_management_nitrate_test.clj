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
   [app.msgbus :as mbus]
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
        calls            (atom [])
        out              (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                                   (swap! calls conj {:topic topic
                                                                      :message message}))]
                           (management-command-with-nitrate! {::th/type :notify-team-change
                                                              :id team-id
                                                              :organization-id organization-id
                                                              :organization-name "Acme Inc"}))]
    (t/is (th/success? out))
    (t/is (= 1 (count @calls)))
    (t/is (= uuid/zero (-> @calls first :topic)))
    (t/is (= {:type :team-org-change
              :team-id team-id
              :team-name nil
              :organization-id organization-id
              :organization-name "Acme Inc"
              :notification nil}
             (-> @calls first :message)))))

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
        org-name       "Acme / Design"
        expected-start (str "[" (d/sanitize-string org-name) "] ")
        calls          (atom [])
        out            (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                                 (swap! calls conj {:topic topic
                                                                    :message message}))]
                         (management-command-with-nitrate! {::th/type :notify-org-deletion
                                                            ::rpc/profile-id (:id profile)
                                                            :teams teams
                                                            :org-name org-name}))
        updated        (map #(th/db-get :team {:id %} {::db/remove-deleted false}) teams)]
    (t/is (th/success? out))
    (t/is (= 2 (count @calls)))
    (doseq [team updated]
      (t/is (false? (:is-default team)))
      (t/is (str/starts-with? (:name team) expected-start)))
    (doseq [call @calls]
      (t/is (= uuid/zero (:topic call)))
      (t/is (= :team-org-change (-> call :message :type)))
      (t/is (= org-name (-> call :message :organization-name)))
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
