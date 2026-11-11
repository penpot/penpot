;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.organization-test
  (:require
   [app.common.flags :as flags]
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
    ;; Team owners can move the team anywhere, or take it out
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms always-organization
                                :profile-id :owner
                                :team-perms {}})))
    ;; Being the organization owner makes no difference
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms always-organization
                                :profile-id :member
                                :team-perms {}})))))

(t/deftest move-team-myorganizations-allows-only-within-same-owner
  (let [my-organizations (assoc organization-perms :permissions {:create-teams "any"
                                                                 :delete-teams "onlyOwners"
                                                                 :move-teams "myOrganizations"})]
    ;; Same owner means same subscription: the organization owner is
    ;; bound by it too
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
    ;; A team owner who is not the organization owner, same rule
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

(t/deftest move-team-myorganizations-denies-taking-the-team-out
  (let [my-organizations (assoc organization-perms :permissions {:create-teams "any"
                                                                 :delete-teams "onlyOwners"
                                                                 :move-teams "myOrganizations"})]
    ;; Taking a team out has no target organization, so no same owner
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms my-organizations
                                 :profile-id :owner
                                 :team-perms {}})))
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms my-organizations
                                 :profile-id :member
                                 :team-perms {}
                                 :target-organization-same-owner? nil})))))

(t/deftest move-team-never-denies-all
  (let [never-organization (assoc organization-perms :permissions {:create-teams "any"
                                                                   :delete-teams "onlyOwners"
                                                                   :move-teams "never"})]
    ;; Only the Admin Console moves teams: the organization owner is
    ;; denied in Penpot too
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

;; --- Attribute restriction gate (:restrictions-met) ---

(t/deftest create-team-owner-bypasses-restriction
  ;; The organization owner keeps the create-teams bypass even when the
  ;; attribute restriction is not met.
  (let [restricted (assoc organization-perms :restrictions-met {:create-teams false})]
    (t/is (true? (cto/allowed? :create-team
                               {:organization-perms restricted
                                :profile-id :owner
                                :team-perms {}})))))

(t/deftest create-team-restriction-narrows-any
  (let [denied (assoc organization-perms :restrictions-met {:create-teams false})
        met (assoc organization-perms :restrictions-met {:create-teams true})]
    ;; Non-owner with "any" but the restriction not met -> denied
    (t/is (false? (cto/allowed? :create-team
                                {:organization-perms denied
                                 :profile-id :member
                                 :team-perms {}})))
    ;; Restriction met -> allowed
    (t/is (true? (cto/allowed? :create-team
                               {:organization-perms met
                                :profile-id :member
                                :team-perms {}})))
    ;; Missing :restrictions-met key -> allowed (old Nitrate keeps behaviour)
    (t/is (true? (cto/allowed? :create-team
                               {:organization-perms organization-perms
                                :profile-id :member
                                :team-perms {}})))))

(t/deftest create-team-onlyme-ignores-restriction
  ;; "onlyMe" is not restrictable: the option itself denies a member even when
  ;; a (bogus) restriction says met, and still allows the owner.
  (let [only-me (assoc organization-perms
                       :permissions {:create-teams "onlyMe" :delete-teams "onlyOwners"}
                       :restrictions-met {:create-teams true})]
    (t/is (false? (cto/allowed? :create-team
                                {:organization-perms only-me
                                 :profile-id :member
                                 :team-perms {}})))
    (t/is (true? (cto/allowed? :create-team
                               {:organization-perms only-me
                                :profile-id :owner
                                :team-perms {}})))))

(t/deftest move-team-restriction-narrows-always
  (let [denied (assoc organization-perms
                      :permissions {:create-teams "any" :delete-teams "onlyOwners" :move-teams "always"}
                      :restrictions-met {:move-teams false})
        met (assoc organization-perms
                   :permissions {:create-teams "any" :delete-teams "onlyOwners" :move-teams "always"}
                   :restrictions-met {:move-teams true})]
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms denied
                                 :profile-id :member
                                 :team-perms {}})))
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms met
                                :profile-id :member
                                :team-perms {}})))))

(t/deftest move-team-restriction-narrows-myorganizations
  (let [denied (assoc organization-perms
                      :permissions {:create-teams "any" :delete-teams "onlyOwners" :move-teams "myOrganizations"}
                      :restrictions-met {:move-teams false})
        met (assoc organization-perms
                   :permissions {:create-teams "any" :delete-teams "onlyOwners" :move-teams "myOrganizations"}
                   :restrictions-met {:move-teams true})]
    ;; Same owner but the restriction not met -> denied
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms denied
                                 :profile-id :member
                                 :team-perms {}
                                 :target-organization-same-owner? true})))
    ;; Same owner and restriction met -> allowed
    (t/is (true? (cto/allowed? :move-team
                               {:organization-perms met
                                :profile-id :member
                                :team-perms {}
                                :target-organization-same-owner? true})))))

(t/deftest move-team-never-ignores-restriction
  (let [never (assoc organization-perms
                     :permissions {:create-teams "any" :delete-teams "onlyOwners" :move-teams "never"}
                     :restrictions-met {:move-teams true})]
    (t/is (false? (cto/allowed? :move-team
                                {:organization-perms never
                                 :profile-id :member
                                 :team-perms {}})))))

(t/deftest send-invitations-restriction-narrows
  (let [denied (assoc organization-perms :restrictions-met {:send-invitations false})
        met (assoc organization-perms :restrictions-met {:send-invitations true})]
    ;; Team admin, ownersAndAdmins, restriction not met -> denied
    (t/is (false? (cto/allowed? :send-invitations
                                {:organization-perms denied
                                 :profile-id :member
                                 :team-perms {:is-owner false :is-admin true}})))
    ;; Restriction met -> allowed
    (t/is (true? (cto/allowed? :send-invitations
                               {:organization-perms met
                                :profile-id :member
                                :team-perms {:is-owner false :is-admin true}})))))

(t/deftest send-invitations-owners-restriction-narrows
  (let [denied (assoc organization-perms
                      :permissions {:create-teams "any" :delete-teams "onlyOwners" :send-invitations "owners"}
                      :restrictions-met {:send-invitations false})]
    ;; Even a team owner is denied when the restriction is not met
    (t/is (false? (cto/allowed? :send-invitations
                                {:organization-perms denied
                                 :profile-id :member
                                 :team-perms {:is-owner true :is-admin true}})))))

(t/deftest delete-team-unaffected-by-restriction
  ;; deleteTeams is not restrictable: a stored restriction must not change it.
  (let [restricted (assoc organization-perms :restrictions-met {:delete-teams false})]
    (t/is (true? (cto/allowed? :delete-team
                               {:organization-perms restricted
                                :profile-id :member
                                :team-perms {:is-owner true :is-admin true}})))))

(t/deftest add-anybody-unaffected-by-restriction
  (let [restricted (assoc organization-perms
                          :permissions {:create-teams "any" :delete-teams "onlyOwners" :new-team-members "anyone"}
                          :restrictions-met {:new-team-members false})]
    (t/is (true? (cto/allowed? :add-anybody-to-team
                               {:organization-perms restricted
                                :profile-id :member
                                :team-perms {}})))))

(t/deftest can-send-invitations-respects-restriction
  (let [denied {:owner-id :owner
                :permissions {:send-invitations "ownersAndAdmins"}
                :restrictions-met {:send-invitations false}}
        met {:owner-id :owner
             :permissions {:send-invitations "ownersAndAdmins"}
             :restrictions-met {:send-invitations true}}]
    (binding [flags/*current* #{:admin-console}]
      (t/is (false? (cto/can-send-invitations? {:organization denied
                                                :profile-id :member
                                                :team-permissions {:is-owner false :is-admin true}})))
      (t/is (true? (cto/can-send-invitations? {:organization met
                                               :profile-id :member
                                               :team-permissions {:is-owner false :is-admin true}}))))))

(t/deftest can-send-invitations-falls-back-without-flag
  ;; Without the admin-console flag, team-level permissions decide and the
  ;; restriction is never consulted.
  (let [organization {:owner-id :owner
                      :permissions {:send-invitations "ownersAndAdmins"}
                      :restrictions-met {:send-invitations false}}]
    (binding [flags/*current* #{}]
      (t/is (true? (cto/can-send-invitations? {:organization organization
                                               :profile-id :member
                                               :team-permissions {:is-owner false :is-admin true}}))))))
