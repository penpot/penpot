;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-nitrate-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.email :as eml]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.nitrate]
   [app.rpc.commands.teams :as teams]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
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

(defn- nitrate-org-summary-only-mock
  [org-summary]
  (fn [_cfg method _params]
    (case method
      :get-org-summary org-summary
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

        organization-id         (uuid/random)
        ;; The user's personal penpot team in the org context
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
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

        ;; The personal team must be renamed with the org prefix and
        ;; unset as a default team.
        (let [team (th/db-get :team {:id your-penpot-id})]
          (t/is (str/starts-with? (:name team) "[Test Org] "))
          (t/is (false? (:is-default team))))))))

(t/deftest leave-org-deletes-org-default-team-when-empty
  (let [profile-owner   (th/create-profile* 1 {:is-active true})
        profile-user    (th/create-profile* 2 {:is-active true})
        org-default-team (th/create-team* 98 {:profile-id (:id profile-user)})

        organization-id          (uuid/random)
        your-penpot-id  (:id org-default-team)

        org-summary     (make-org-summary
                         :organization-id            organization-id
                         :organization-name          "Test Org"
                         :owner-id          (:id profile-owner)
                         :your-penpot-teams [your-penpot-id]
                         :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
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

        organization-id           (uuid/random)
        your-penpot-id   (:id org-default-team)

        org-summary      (make-org-summary
                          :organization-id            organization-id
                          :organization-name          "Test Org"
                          :owner-id          (:id profile-owner)
                          :your-penpot-teams [your-penpot-id]
                          :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-user)
                  :id          organization-id
                  :name        "Test Org"
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

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
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

(t/deftest leave-org-with-ownership-transfer
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user owns team1; profile-owner is also a member
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
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

(t/deftest leave-org-exit-as-non-owner
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-owner owns team1; profile-user is a non-owner member
        team1          (th/create-team* 1 {:profile-id (:id profile-owner)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-user)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
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

(t/deftest get-leave-org-summary-counts-default-team-as-delete-when-empty
  (let [profile-owner    (th/create-profile* 1 {:is-active true})
        profile-user     (th/create-profile* 2 {:is-active true})
        org-default-team (th/create-team* 97 {:profile-id (:id profile-user)})

        organization-id  (uuid/random)
        your-penpot-id   (:id org-default-team)
        org-summary      (make-org-summary
                          :organization-id organization-id
                          :organization-name "Test Org"
                          :owner-id (:id profile-owner)
                          :your-penpot-teams [your-penpot-id]
                          :org-teams [])]

    (with-redefs [nitrate/call (nitrate-org-summary-only-mock org-summary)]
      (let [out (th/command! {::th/type :get-leave-org-summary
                              ::rpc/profile-id (:id profile-user)
                              :id organization-id
                              :default-team-id your-penpot-id})]
        (t/is (th/success? out))
        (t/is (= {:teams-to-delete 0
                  :teams-to-transfer 0
                  :teams-to-exit 0
                  :teams-to-detach 0}
                 (:result out)))))))

(t/deftest get-leave-org-summary-counts-default-team-as-keep-when-has-files
  (let [profile-owner    (th/create-profile* 1 {:is-active true})
        profile-user     (th/create-profile* 2 {:is-active true})
        org-default-team (th/create-team* 96 {:profile-id (:id profile-user)})
        project          (th/create-project* 96 {:profile-id (:id profile-user)
                                                 :team-id (:id org-default-team)})
        _                (th/create-file* 96 {:profile-id (:id profile-user)
                                              :project-id (:id project)})
        extra-team       (th/create-team* 95 {:profile-id (:id profile-user)})

        organization-id  (uuid/random)
        your-penpot-id   (:id org-default-team)
        org-summary      (make-org-summary
                          :organization-id organization-id
                          :organization-name "Test Org"
                          :owner-id (:id profile-owner)
                          :your-penpot-teams [your-penpot-id]
                          :org-teams [(:id extra-team)])]

    (with-redefs [nitrate/call (nitrate-org-summary-only-mock org-summary)]
      (let [out (th/command! {::th/type :get-leave-org-summary
                              ::rpc/profile-id (:id profile-user)
                              :id organization-id
                              :default-team-id your-penpot-id})]
        (t/is (th/success? out))
        ;; extra-team is deletable, default team has files and is preserved.
        (t/is (= {:teams-to-delete 1
                  :teams-to-transfer 0
                  :teams-to-exit 0
                  :teams-to-detach 1}
                 (:result out)))))))

(t/deftest leave-org-error-org-owner-cannot-leave
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-owner)})
        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        ;; profile-owner IS the org owner in the org-summary
        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
                  ::rpc/profile-id (:id profile-owner)
                  :id          organization-id
                  :name        "Test Org"
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
        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; Pass a random UUID that is not in the your-penpot-teams list
      (let [data {::th/type        :leave-org
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

(t/deftest calculate-valid-teams-no-org-teams
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
        ;; default-id is not in org-teams at all
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

(t/deftest leave-org-combined-delete-and-leave
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
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1) (:id team2) (:id team3)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      (let [data {::th/type        :leave-org
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
(t/deftest leave-org-error-teams-to-delete-incomplete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user is the sole owner/member of both team1 and team2
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        team2          (th/create-team* 2 {:profile-id (:id profile-user)})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1) (:id team2)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; Only team1 is listed; team2 is also a sole-owner team and must be included
      (let [data {::th/type        :leave-org
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

(t/deftest leave-org-error-cannot-delete-multi-member-team
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; team1 has two members: profile-user (owner) and profile-owner (editor)
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; team1 has 2 members so it is not a valid deletion candidate
      (let [data {::th/type        :leave-org
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

(t/deftest leave-org-error-teams-to-leave-incomplete
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-user owns team1, which also has profile-owner as editor
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; team1 must be transferred (owner + multiple members) but is absent
      (let [data {::th/type        :leave-org
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

(t/deftest leave-org-error-reassign-to-self
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        team1          (th/create-team* 1 {:profile-id (:id profile-user)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-owner)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; reassign-to points to the profile that is leaving — not allowed
      (let [data {::th/type        :leave-org
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

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; profile-other is not a member of team1
      (let [data {::th/type        :leave-org
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

(t/deftest all-team-members-in-orgs-returns-org-id->boolean-map
  (let [profile-user  (th/create-profile* 201 {:is-active true})
        profile-other (th/create-profile* 202 {:is-active true})
        team          (th/create-team* 201 {:profile-id (:id profile-user)})
        _             (th/create-team-role* {:team-id (:id team)
                                             :profile-id (:id profile-other)
                                             :role :editor})
        team-member-ids (->> (th/db-query :team-profile-rel {:team-id (:id team)})
                             (map :profile-id)
                             (into #{}))
        org-id-1      (uuid/random)
        org-id-2      (uuid/random)
        calls         (atom [])]
    (with-redefs [cf/flags (conj cf/flags :nitrate)
                  nitrate/call (fn [_cfg method params]
                                 (swap! calls conj [method params])
                                 (case method
                                   :get-org-membership {:is-member true
                                                        :organization-id (:organization-id params)}
                                   :get-org-members (get {org-id-1 (vec team-member-ids)
                                                          org-id-2 [(:id profile-user)]}
                                                         (:organization-id params)
                                                         [])
                                   nil))]
      (let [out (th/command! {::th/type :all-team-members-in-orgs
                              ::rpc/profile-id (:id profile-user)
                              :team-id (:id team)
                              :organization-ids [org-id-1 org-id-2]})
            methods (map first @calls)
            membership-calls (count (filter #(= :get-org-membership %) methods))
            get-members-calls (count (filter #(= :get-org-members %) methods))]
        (t/is (th/success? out))
        (t/is (= {org-id-1 true
                  org-id-2 false}
                 (:result out)))
        (t/is (= 2 membership-calls))
        (t/is (= 2 get-members-calls))))))

(t/deftest all-team-members-in-orgs-fails-before-fetching-org-members
  (let [profile-user  (th/create-profile* 203 {:is-active true})
        team          (th/create-team* 203 {:profile-id (:id profile-user)})
        org-id-1      (uuid/random)
        org-id-2      (uuid/random)
        calls         (atom [])]
    (with-redefs [cf/flags (conj cf/flags :nitrate)
                  nitrate/call (fn [_cfg method params]
                                 (swap! calls conj [method params])
                                 (case method
                                   :get-org-membership (if (= (:organization-id params) org-id-2)
                                                         {:is-member false
                                                          :organization-id (:organization-id params)}
                                                         {:is-member true
                                                          :organization-id (:organization-id params)})
                                   :get-org-members []
                                   nil))]
      (let [out (th/command! {::th/type :all-team-members-in-orgs
                              ::rpc/profile-id (:id profile-user)
                              :team-id (:id team)
                              :organization-ids [org-id-1 org-id-2]})
            methods (map first @calls)]
        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :user-doesnt-belong-organization (th/ex-code (:error out))))
        (t/is (= 0 (count (filter #(= :get-org-members %) methods))))))))

(t/deftest leave-org-error-reassign-on-non-owned-team
  (let [profile-owner  (th/create-profile* 1 {:is-active true})
        profile-user   (th/create-profile* 2 {:is-active true})
        ;; profile-owner owns team1; profile-user is just a non-owner member
        team1          (th/create-team* 1 {:profile-id (:id profile-owner)})
        _              (th/create-team-role* {:team-id    (:id team1)
                                              :profile-id (:id profile-user)
                                              :role       :editor})
        org-default-team (th/create-team* 99 {:profile-id (:id profile-user)})

        organization-id         (uuid/random)
        your-penpot-id (:id org-default-team)

        org-summary    (make-org-summary
                        :organization-id            organization-id
                        :organization-name          "Test Org"
                        :owner-id          (:id profile-owner)
                        :your-penpot-teams [your-penpot-id]
                        :org-teams         [(:id team1)])]

    (with-redefs [nitrate/call (nitrate-call-mock org-summary)]
      ;; profile-user is not the owner so providing reassign-to is invalid
      (let [data {::th/type        :leave-org
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

(defn- add-team-to-org-nitrate-mock
  [{:keys [org-id org-summary org-perms owner-id team-id sso-active?]}]
  (fn [_cfg method params]
    (case method
      :get-org-membership (if (= (:profile-id params) owner-id)
                            {:is-member true :organization-id org-id}
                            {:is-member false :organization-id org-id})
      :get-org-members [owner-id]
      :get-team-org {:organization nil}
      :get-org-permissions org-perms
      :set-team-org {:id team-id}
      :get-org-sso {:active sso-active?}
      :get-org-summary (assoc org-summary :teams [{:id team-id}])
      :add-profile-to-org {:is-member true}
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
        org-id     (uuid/random)
        org-name   "SSO Org"
        org-summary {:id org-id
                     :name org-name
                     :owner-id (:id owner)
                     :teams []}
        org-perms  {:owner-id (:id owner)
                    :permissions {:create-teams "any"
                                  :move-teams "always"
                                  :new-team-members "members"}}
        sent       (atom [])]

    (th/db-insert! :team-invitation
                   {:id (uuid/random)
                    :team-id (:id team)
                    :org-id nil
                    :email-to "external301@example.com"
                    :created-by (:id owner)
                    :role "editor"
                    :valid-until (ct/in-future "48h")})

    (with-redefs [cf/flags (conj cf/flags :nitrate)
                  nitrate/call (add-team-to-org-nitrate-mock
                                {:org-id org-id
                                 :org-summary org-summary
                                 :org-perms org-perms
                                 :owner-id (:id owner)
                                 :team-id (:id team)
                                 :sso-active? true})
                  teams/initialize-user-in-nitrate-org (fn [& _] nil)
                  eml/send! (fn [params] (swap! sent conj params))]
      (let [out (th/command! {::th/type :add-team-to-organization
                              ::rpc/profile-id (:id owner)
                              :team-id (:id team)
                              :organization-id org-id})]
        (t/is (th/success? out))))

    (let [emails (->> @sent (map :to) set)]
      (t/is (= 2 (count @sent)))
      (t/is (= #{"member302@example.com" "external301@example.com"} emails))
      (doseq [email-params @sent]
        (t/is (= org-name (:organization-name email-params)))
        (t/is (= eml/organization-setup-sso (::eml/factory email-params)))))))

(t/deftest add-team-to-organization-skips-sso-emails-when-sso-inactive
  (let [owner      (th/create-profile* 303 {:is-active true :email "owner303@example.com"})
        member     (th/create-profile* 304 {:is-active true :email "member304@example.com"})
        team       (th/create-team* 303 {:profile-id (:id owner)})
        _          (th/create-team-role* {:team-id (:id team)
                                           :profile-id (:id member)
                                           :role :editor})
        org-id     (uuid/random)
        org-summary {:id org-id
                     :name "No SSO Org"
                     :owner-id (:id owner)
                     :teams []}
        org-perms  {:owner-id (:id owner)
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

    (with-redefs [cf/flags (conj cf/flags :nitrate)
                  nitrate/call (add-team-to-org-nitrate-mock
                                {:org-id org-id
                                 :org-summary org-summary
                                 :org-perms org-perms
                                 :owner-id (:id owner)
                                 :team-id (:id team)
                                 :sso-active? false})
                  teams/initialize-user-in-nitrate-org (fn [& _] nil)
                  eml/send! (fn [params] (swap! sent conj params))]
      (let [out (th/command! {::th/type :add-team-to-organization
                              ::rpc/profile-id (:id owner)
                              :team-id (:id team)
                              :organization-id org-id})]
        (t/is (th/success? out))
        (t/is (empty? @sent))))))
