;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-org-owner-permissions-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- org-data
  [org-id owner-id]
  {:id            org-id
   :name          "Acme"
   :slug          "acme"
   :owner-id      owner-id
   :avatar-bg-url "http://example.com/avatar.png"
   :permissions   {}})

(defn- with-org-owner-access
  [{:keys [org-owner-id org-id team-id]} f]
  (with-redefs [cf/flags (conj cf/flags :nitrate)
                nitrate/organization-owner-of-team?
                (fn [_cfg profile-id candidate-team-id]
                  (and (= org-owner-id profile-id)
                       (= team-id candidate-team-id)))

                nitrate/call
                (fn [_cfg method params]
                  (case method
                    :get-owned-orgs
                    [{:id org-id
                      :name "Acme"
                      :owner-id org-owner-id
                      :teams [{:id team-id :is-your-penpot false}]}]

                    :get-team-org
                    (if (= team-id (:team-id params))
                      {:id team-id
                       :is-your-penpot false
                       :organization (org-data org-id org-owner-id)}
                      {:id (:team-id params)
                       :is-your-penpot false
                       :organization nil})))]
    (f)))

(t/deftest org-owner-access-disabled-without-nitrate-flag
  (let [team-owner  (th/create-profile* 1)
        org-owner   (th/create-profile* 2)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})]

    (let [out   (th/command! {::th/type :get-projects
                              ::rpc/profile-id (:id org-owner)
                              :team-id (:id target-team)})
          error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :not-found)))))

(t/deftest non-member-org-owner-gets-viewer-access-to-org-team
  (let [team-owner  (th/create-profile* 1)
        org-owner   (th/create-profile* 2)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})
        project     (th/create-project* 1 {:profile-id (:id team-owner)
                                           :team-id (:id target-team)})
        file        (th/create-file* 1 {:profile-id (:id team-owner)
                                        :project-id (:id project)})
        org-id      (uuid/next)]

    (with-org-owner-access {:org-owner-id (:id org-owner)
                            :org-id org-id
                            :team-id (:id target-team)}
      (fn []
        ;; The team is not listed for a non-member, even though the org
        ;; owner can access it directly.
        (let [out (th/command! {::th/type :get-teams
                                ::rpc/profile-id (:id org-owner)})]
          (t/is (nil? (:error out)))
          (t/is (not-any? #(= (:id target-team) (:id %)) (:result out))))

        (let [out  (th/command! {::th/type :get-team
                                 ::rpc/profile-id (:id org-owner)
                                 :id (:id target-team)})
              team (:result out)]
          (t/is (nil? (:error out)))
          (t/is (= (:id target-team) (:id team)))
          (t/is (false? (get-in team [:permissions :is-owner])))
          (t/is (false? (get-in team [:permissions :is-admin])))
          (t/is (false? (get-in team [:permissions :can-edit])))
          (t/is (= org-id (get-in team [:organization :id])))
          (t/is (= "Acme" (get-in team [:organization :name]))))

        (let [out     (th/command! {::th/type :get-team-members
                                    ::rpc/profile-id (:id org-owner)
                                    :team-id (:id target-team)})
              members (:result out)]
          (t/is (nil? (:error out)))
          (t/is (some #(= (:id team-owner) (:id %)) members))
          (t/is (not-any? #(= (:id org-owner) (:id %)) members)))

        (let [out (th/command! {::th/type :get-projects
                                ::rpc/profile-id (:id org-owner)
                                :team-id (:id target-team)})]
          (t/is (nil? (:error out)))
          (t/is (some #(= (:id project) (:id %)) (:result out))))

        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id org-owner)
                                :id (:id file)})]
          (t/is (nil? (:error out)))
          (t/is (= (:id file) (get-in out [:result :id])))
          (t/is (false? (get-in out [:result :permissions :can-edit]))))

        (let [out   (th/command! {::th/type :rename-project
                                  ::rpc/profile-id (:id org-owner)
                                  :id (:id project)
                                  :name "Nope"})
              error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :not-found)))))))

(t/deftest org-owner-member-keeps-team-role
  (let [team-owner  (th/create-profile* 1)
        org-owner   (th/create-profile* 2)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})
        org-id      (uuid/next)]

    (th/create-team-role* {:team-id (:id target-team)
                           :profile-id (:id org-owner)
                           :role :editor})

    (with-org-owner-access {:org-owner-id (:id org-owner)
                            :org-id org-id
                            :team-id (:id target-team)}
      (fn []
        (let [out  (th/command! {::th/type :get-team
                                 ::rpc/profile-id (:id org-owner)
                                 :id (:id target-team)})
              team (:result out)]
          (t/is (nil? (:error out)))
          (t/is (false? (get-in team [:permissions :is-owner])))
          (t/is (false? (get-in team [:permissions :is-admin])))
          (t/is (true? (get-in team [:permissions :can-edit]))))))))
