;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-nitrate-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as-alias db]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-org-summary
  [& {:keys [org-id org-name owner-id your-penpot-teams org-teams]
      :or   {your-penpot-teams [] org-teams []}}]
  {:id       org-id
   :name     org-name
   :owner-id owner-id
   :teams    (into
              (mapv (fn [id] {:id id :is-your-penpot true}) your-penpot-teams)
              (mapv (fn [id] {:id id :is-your-penpot false}) org-teams))})

(defn- nitrate-call-mock
  "Creates a mock for nitrate/call that returns the given org-summary for
  :get-org-summary, a valid membership for :get-org-membership, and nil for
  any other method."
  [org-summary]
  (fn [_cfg method _params]
    (case method
      :get-org-summary org-summary
      :get-org-membership {:is-member true
                           :organization-id (:id org-summary)}
      nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest leave-org-happy-path-no-extra-teams
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})

        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})
        project          (th/create-project* 99 {:profile-id (:id profile-user)
                                                 :team-id (:id org-default-team)})
        _                (th/create-file* 99 {:profile-id (:id profile-user)
                                              :project-id (:id project)})

        org-id         (uuid/random)
        ;; The user's personal penpot team in the org context
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (nil? (:result out)))

        ;; The personal team must be renamed with the org prefix and
        ;; unset as a default team.
        (let [team (th/db-get :team {:id your-penpot-id})]
          (t/is (str/starts-with? (:name team) "[Test Org] "))
          (t/is (false? (:is-default team))))))))

(t/deftest leave-org-deletes-org-default-team-when-empty
  (let [profile-owner   (th/create-profile* 1 {:is-active true})
        profile-user    (th/create-profile* 2 {:is-active true})
        org-default-team (th/create-team* 98 {:profile-id (:id profile-user)})

        org-id          (uuid/random)
        your-penpot-id  (:id org-default-team)

        org-summary     (make-org-summary
                         :org-id            org-id
                         :org-name          "Test Org"
                         :owner-id          (:id profile-owner)
                         :your-penpot-teams [your-penpot-id]
                         :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (th/success? out))

        ;; Empty org default team should be soft-deleted.
        (let [team (th/db-get :team {:id your-penpot-id} {::db/remove-deleted false})]
          (t/is (some? (:deleted-at team))))))))

(t/deftest leave-org-keeps-and-renames-org-default-team-when-has-files
  (let [profile-owner    (th/create-profile* 1 {:is-active true})
        profile-user     (th/create-profile* 2 {:is-active true})
        org-default-team (th/create-team* 97 {:profile-id (:id profile-user)})
        project          (th/create-project* 97 {:profile-id (:id profile-user)
                                                 :team-id (:id org-default-team)})
        _                (th/create-file* 97 {:profile-id (:id profile-user)
                                              :project-id (:id project)})

        org-id           (uuid/random)
        your-penpot-id   (:id org-default-team)

        org-summary      (make-org-summary
                          :org-id            org-id
                          :org-name          "Test Org"
                          :owner-id          (:id profile-owner)
                          :your-penpot-teams [your-penpot-id]
                          :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (th/success? out))

        ;; Non-empty org default team should remain and be renamed.
        (let [team (th/db-get :team {:id your-penpot-id})]
          (t/is (str/starts-with? (:name team) "[Test Org] "))
          (t/is (false? (:is-default team)))
          (t/is (nil? (:deleted-at team))))))))

(t/deftest leave-org-with-teams-to-delete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user is the sole owner/member of team1
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete [(:id team1)]
                  :teams-to-leave  []}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))

        ;; team1 should be scheduled for deletion (deleted-at set)
        (let [team (th/db-get :team {:id (:id team1)} {::db/remove-deleted false})]
          (t/is (some? (:deleted-at team))))))))

(t/deftest leave-org-with-ownership-transfer
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user owns team1; profile-owner is also a member
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
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

(t/deftest leave-org-exit-as-non-owner
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-owner owns team1; profile-user is a non-owner member
        team1          (th/create-team* 1 {:profile-id (:id profile-owner)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-user)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
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

(t/deftest leave-org-error-org-owner-cannot-leave
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-owner)})
        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        ;; profile-owner IS the org owner in the org-summary
        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-owner)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :org-owner-cannot-leave (th/ex-code (:error out))))))))

(t/deftest leave-org-error-invalid-default-team-id
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})
        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; Pass a random UUID that is not in the your-penpot-teams list
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id (uuid/random)
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-org-error-teams-to-delete-incomplete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user is the sole owner/member of both team1 and team2
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        team2          (th/create-team* 2 {:profile-id (:id profile-user)})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1) (:id team2)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; Only team1 is listed; team2 is also a sole-owner team and must be included
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete [(:id team1)]
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-org-error-cannot-delete-multi-member-team
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; team1 has two members: profile-user (owner) and profile-owner (editor)
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; team1 has 2 members so it is not a valid deletion candidate
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete [(:id team1)]
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-org-error-teams-to-leave-incomplete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user owns team1, which also has profile-owner as editor
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; team1 must be transferred (owner + multiple members) but is absent
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  []}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-org-error-reassign-to-self
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; reassign-to points to the profile that is leaving — not allowed
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1) :reassign-to (:id profile-user)}]}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-org-error-reassign-to-non-member
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        profile-other  (th/create-profile* 3 {:is-active true})
        ;; team1 has profile-user (owner) and profile-owner (editor) — NOT profile-other
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; profile-other is not a member of team1
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1) :reassign-to (:id profile-other)}]}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))

(t/deftest leave-org-error-reassign-on-non-owned-team
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-owner owns team1; profile-user is just a non-owner member
        team1          (th/create-team* 1 {:profile-id (:id profile-owner)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-user)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        org-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :org-id            org-id
                        :org-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; profile-user is not the owner so providing reassign-to is invalid
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :org-id          org-id
                  :default-team-id your-penpot-id
                  :teams-to-delete []
                  :teams-to-leave  [{:id (:id team1) :reassign-to (:id profile-owner)}]}
            out  (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :not-valid-teams (th/ex-code (:error out))))))))
