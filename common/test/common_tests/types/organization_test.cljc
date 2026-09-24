;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.organization-test
  (:require
   [app.common.types.organization :as cto]
   [clojure.test :as t]))

(def organization-perms
  {:owner-id :owner
   :permissions {:create-teams "any"
                 :delete-teams "onlyOwners"
                 :send-invitations "ownersAndAdmins"}})

(t/deftest unknown-action-is-denied
  (t/is (false? (cto/allowed? :unknown
                              {:organization-perms organization-perms
                               :profile-id :member
                               :team-perms {:is-admin true}}))))

(t/deftest organization-owner-is-allowed-for-create-and-delete
  (t/is (true? (cto/allowed? :create-team
                             {:organization-perms organization-perms
                              :profile-id :owner
                              :team-perms {:is-admin false}})))
  (t/is (true? (cto/allowed? :delete-team
                             {:organization-perms organization-perms
                              :profile-id :owner
                              :team-perms {:is-admin false}}))))

(t/deftest create-team-permission-rules
  (t/is (true? (cto/allowed? :create-team
                             {:organization-perms organization-perms
                              :profile-id :member
                              :team-perms {:is-admin false}})))
  (t/is (false? (cto/allowed? :create-team
                              {:organization-perms (assoc organization-perms :permissions {:create-teams "none"
                                                                                           :delete-teams "onlyOwners"})
                               :profile-id :member
                               :team-perms {:is-admin false}}))))

(t/deftest delete-team-onlyowners-allows-only-team-owners
  (t/is (true? (cto/allowed? :delete-team
                             {:organization-perms organization-perms
                              :profile-id :member
                              :team-perms {:is-owner true :is-admin true}})))
  (t/is (false? (cto/allowed? :delete-team
                              {:organization-perms organization-perms
                               :profile-id :member
                               :team-perms {:is-admin true}})))
  (t/is (false? (cto/allowed? :delete-team
                              {:organization-perms (assoc organization-perms :permissions {:create-teams "any"
                                                                                           :delete-teams "invalid-value"})
                               :profile-id :member
                               :team-perms {:is-admin true}}))))

(t/deftest delete-team-onlyme-still-allows-organization-owner
  (let [only-me-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                     :delete-teams "onlyMe"})]
    (t/is (true? (cto/allowed? :delete-team
                               {:organization-perms only-me-organization
                                :profile-id :owner
                                :team-perms {:is-owner false :is-admin false}})))
    (t/is (false? (cto/allowed? :delete-team
                                {:organization-perms only-me-organization
                                 :profile-id :member
                                 :team-perms {:is-owner true :is-admin true}})))))

(t/deftest move-team-always-allows-any-organization-owner-or-all-users
  (let [always-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                    :delete-teams "onlyOwners"
                                                                    :move-teams "always"})]
    ;; Organization owner should always be allowed
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms always-organization
                                :profile-id :owner
                                :team-perms {}})))
    ;; Regular member should be allowed when move-teams is "always"
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms always-organization
                                :profile-id :member
                                :team-perms {}})))))

(t/deftest move-team-myorganizations-allows-only-within-same-owner
  (let [my-organizations (assoc organization-perms :permissions {:create-teams "any"
                                                                 :delete-teams "onlyOwners"
                                                                 :move-teams "myOrganizations"})]
    ;; Organization owner must also stay within same-owner organizations
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms my-organizations
                                 :profile-id :owner
                                 :team-perms {}
                                 :target-organization-same-owner? false})))
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms my-organizations
                                :profile-id :owner
                                :team-perms {}
                                :target-organization-same-owner? true})))
    ;; Regular member should be allowed only if target has same owner
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms my-organizations
                                :profile-id :member
                                :team-perms {}
                                :target-organization-same-owner? true})))
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms my-organizations
                                 :profile-id :member
                                 :team-perms {}
                                 :target-organization-same-owner? false})))))

(t/deftest move-team-never-denies-all
  (let [never-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                   :delete-teams "onlyOwners"
                                                                   :move-teams "never"})]
    ;; Even organization owner should be denied
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms never-organization
                                 :profile-id :owner
                                 :team-perms {}})))
    ;; Regular member should be denied
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms never-organization
                                 :profile-id :member
                                 :team-perms {}})))))

(t/deftest move-team-defaults-to-always
  (let [default-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                     :delete-teams "onlyOwners"})]
    ;; Should default to "always" when not specified
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms default-organization
                                :profile-id :member
                                :team-perms {}})))))

(t/deftest send-invitations-defaults-to-owners-and-admins
  (let [default-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                     :delete-teams "onlyOwners"})]
    (t/is (true? (cto/allowed? :send-invitations
                               {:organization-perms default-organization
                                :profile-id :owner
                                :team-perms {:is-owner true :is-admin false}})))
    (t/is (true? (cto/allowed? :send-invitations
                               {:organization-perms default-organization
                                :profile-id :member
                                :team-perms {:is-owner false :is-admin true}})))
    (t/is (false? (cto/allowed? :send-invitations
                                {:organization-perms default-organization
                                 :profile-id :member
                                 :team-perms {:is-owner false :is-admin false}})))))

(t/deftest send-invitations-owners-allows-only-team-owners
  (let [only-owners-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                         :delete-teams "onlyOwners"
                                                                         :send-invitations "owners"})]
    (t/is (true? (cto/allowed? :send-invitations
                               {:organization-perms only-owners-organization
                                :profile-id :member
                                :team-perms {:is-owner true :is-admin true}})))
    (t/is (false? (cto/allowed? :send-invitations
                                {:organization-perms only-owners-organization
                                 :profile-id :owner
                                 :team-perms {:is-owner false :is-admin false}})))
    (t/is (false? (cto/allowed? :send-invitations
                                {:organization-perms only-owners-organization
                                 :profile-id :member
                                 :team-perms {:is-owner false :is-admin true}})))))

(t/deftest send-invitations-invalid-value-is-denied
  (let [invalid-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                     :delete-teams "onlyOwners"
                                                                     :send-invitations "invalid-value"})]
    (t/is (false? (cto/allowed? :send-invitations
                                {:organization-perms invalid-organization
                                 :profile-id :member
                                 :team-perms {:is-owner true :is-admin true}})))))
