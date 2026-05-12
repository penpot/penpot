;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.nitrate-permissions-test
  (:require
   [app.common.types.nitrate-permissions :as nitrate-perms]
   [clojure.test :as t]))

(def org-perms
  {:owner-id :owner
   :permissions {:create-teams "any"
                 :delete-teams "ownersAndAdmins"}})

(t/deftest unknown-action-is-denied
  (t/is (false? (nitrate-perms/allowed? :unknown
                                        {:org-perms org-perms
                                         :profile-id :member
                                         :team-perms {:is-admin true}}))))

(t/deftest owner-is-always-allowed
  (t/is (true? (nitrate-perms/allowed? :create-team
                                       {:org-perms org-perms
                                        :profile-id :owner
                                        :team-perms {:is-admin false}})))
  (t/is (true? (nitrate-perms/allowed? :delete-team
                                       {:org-perms org-perms
                                        :profile-id :owner
                                        :team-perms {:is-admin false}}))))

(t/deftest create-team-permission-rules
  (t/is (true? (nitrate-perms/allowed? :create-team
                                       {:org-perms org-perms
                                        :profile-id :member
                                        :team-perms {:is-admin false}})))
  (t/is (false? (nitrate-perms/allowed? :create-team
                                        {:org-perms (assoc org-perms :permissions {:create-teams "none"
                                                                                   :delete-teams "ownersAndAdmins"})
                                         :profile-id :member
                                         :team-perms {:is-admin false}}))))

(t/deftest delete-team-requires-admin-and-policy
  (t/is (false? (nitrate-perms/allowed? :delete-team
                                        {:org-perms org-perms
                                         :profile-id :member
                                         :team-perms {:is-admin false}})))
  (t/is (true? (nitrate-perms/allowed? :delete-team
                                       {:org-perms org-perms
                                        :profile-id :member
                                        :team-perms {:is-admin true}})))
  (t/is (false? (nitrate-perms/allowed? :delete-team
                                        {:org-perms (assoc org-perms :permissions {:create-teams "any"
                                                                                   :delete-teams "onlyOwners"})
                                         :profile-id :member
                                         :team-perms {:is-admin true}}))))
