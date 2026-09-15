;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.instance-admin-test
  (:require
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest instance-administration-requires-active-configured-admin
  (let [account (th/create-profile* 1 {:is-active true})
        params  {::th/type :get-instance-users ::rpc/profile-id (:id account)}]
    (t/is (th/ex-of-code? (:error (th/command! params)) :only-admins-allowed))
    (with-redefs [cf/config (assoc cf/config :admins #{(:email account)})]
      (t/is (nil? (:error (th/command! params))))
      (db/update! th/*pool* :profile {:is-blocked true} {:id (:id account)})
      (t/is (th/ex-of-code? (:error (th/command! params)) :only-admins-allowed)))))

(t/deftest user-list-is-paginated-and-does-not-expose-credentials
  (let [admin (th/create-profile* 1 {:is-active true})
        user  (th/create-profile* 2)]
    (with-redefs [cf/config (assoc cf/config :admins #{(:email admin)})]
      (let [out (th/command! {::th/type :get-instance-users ::rpc/profile-id (:id admin)
                              :search (:email user) :limit 1})
            row (first (get-in out [:result :users]))]
        (t/is (nil? (:error out)))
        (t/is (= 1 (get-in out [:result :total])))
        (t/is (= (:id user) (:id row)))
        (t/is (= (:default-team-id user) (:id (first (:teams row)))))
        (t/is (not-any? #(contains? row %) [:password :props :default-team-id]))))))

(t/deftest administrator-can-manage-shared-memberships-without-invitations
  (let [admin  (th/create-profile* 1 {:is-active true})
        owner  (th/create-profile* 2 {:is-active true})
        member (th/create-profile* 3 {:is-active true})
        team   (:result (th/command! {::th/type :create-team ::rpc/profile-id (:id owner)
                                      :name "Shared design"}))
        params {::th/type :set-instance-user-membership ::rpc/profile-id (:id admin)
                :member-id (:id member) :kind :team :target-id (:id team)}]
    (t/is (th/ex-of-code? (:error (th/command! (assoc params :role :editor))) :only-admins-allowed))
    (with-redefs [cf/config (assoc cf/config :admins #{(:email admin)})]
      (doseq [role [:editor :viewer :admin]]
        (let [out (th/command! (assoc params :role role))
              rel (th/db-get :team-profile-rel {:team-id (:id team) :profile-id (:id member)})]
          (t/is (nil? (:error out)))
          (t/is (= (= role :admin) (:is-admin rel)))
          (t/is (= (not= role :viewer) (:can-edit rel)))))
      (t/is (nil? (:error (th/command! (assoc params :role :none)))))
      (t/is (empty? (db/query th/*pool* :team-profile-rel
                              {:team-id (:id team) :profile-id (:id member)})))
      (t/is (th/ex-of-code? (:error (th/command! (assoc params :member-id (:id owner) :role :none)))
                            :protected-membership))
      (t/is (th/ex-of-code? (:error (th/command! (assoc params :target-id (:default-team-id member) :role :none)))
                            :protected-membership))
      (t/is (empty? (db/query th/*pool* :team-invitation {:team-id (:id team)}))))))

(t/deftest administrator-can-manage-explicit-project-affiliations
  (let [admin  (th/create-profile* 1 {:is-active true})
        member (th/create-profile* 2 {:is-active true})
        team   (:result (th/command! {::th/type :create-team ::rpc/profile-id (:id admin) :name "Shared"}))
        params {::th/type :set-instance-user-membership ::rpc/profile-id (:id admin)
                :member-id (:id member) :kind :project :target-id (:default-project-id team)}]
    (with-redefs [cf/config (assoc cf/config :admins #{(:email admin)})]
      (t/is (th/ex-of-code? (:error (th/command! (assoc params :role :editor))) :team-membership-required))
      (t/is (nil? (:error (th/command! (assoc params :kind :team :target-id (:id team) :role :viewer)))))
      (t/is (nil? (:error (th/command! (assoc params :role :editor)))))
      (let [file (th/create-file* 1 {:profile-id (:id admin) :project-id (:default-project-id team)})
            out (th/command! {::th/type :get-file ::rpc/profile-id (:id member) :id (:id file)})]
        (t/is (nil? (:error out)))
        (t/is (get-in out [:result :permissions :can-edit])))
      (let [out (th/command! {::th/type :get-instance-users ::rpc/profile-id (:id admin) :search (:email member)})]
        (t/is (some #(= (:default-project-id team) (:id %))
                    (get-in out [:result :users 0 :projects]))))
      (t/is (nil? (:error (th/command! (assoc params :role :none)))))
      (t/is (empty? (db/query th/*pool* :project-profile-rel
                              {:project-id (:default-project-id team) :profile-id (:id member)}))))))
