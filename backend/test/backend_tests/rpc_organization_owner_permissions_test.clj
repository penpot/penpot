;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-organization-owner-permissions-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.msgbus :as mbus]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- organization-data
  [organization-id owner-id]
  {:id            organization-id
   :name          "Acme"
   :slug          "acme"
   :owner-id      owner-id
   :avatar-bg-url "http://example.com/avatar.png"
   :permissions   {}})

(defn- with-organization-owner-access
  [{:keys [organization-owner-id organization-id team-id]} f]
  (with-redefs [cf/flags (conj cf/flags :nitrate)
                nitrate/organization-owner-of-team?
                (fn [_cfg profile-id candidate-team-id]
                  (and (= organization-owner-id profile-id)
                       (= team-id candidate-team-id)))

                nitrate/call
                (fn [_cfg method params]
                  (case method
                    :get-owned-organizations
                    [{:id organization-id
                      :name "Acme"
                      :owner-id organization-owner-id
                      :teams [{:id team-id :is-your-penpot false}]}]

                    :get-team-organization
                    (if (= team-id (:team-id params))
                      {:id team-id
                       :is-your-penpot false
                       :organization (organization-data organization-id organization-owner-id)}
                      {:id (:team-id params)
                       :is-your-penpot false
                       :organization nil})))]
    (f)))

(defn- with-captured-messages
  "Runs `f` with the msgbus publications collected on `messages`."
  [messages f]
  (with-redefs [mbus/pub! (fn [_instance & {:keys [topic message]}]
                            (swap! messages conj {:topic topic :message message})
                            nil)]
    (f)))

(defn- messages-for
  [messages profile-id]
  (->> @messages
       (filter #(= profile-id (:topic %)))
       (mapv :message)))

(t/deftest organization-owner-access-disabled-without-nitrate-flag
  (let [team-owner  (th/create-profile* 1)
        organization-owner   (th/create-profile* 2)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})]

    (let [out   (th/command! {::th/type :get-projects
                              ::rpc/profile-id (:id organization-owner)
                              :team-id (:id target-team)})
          error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :not-found)))))

(t/deftest non-member-organization-owner-gets-viewer-access-to-organization-team
  (let [team-owner  (th/create-profile* 1)
        organization-owner   (th/create-profile* 2)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})
        project     (th/create-project* 1 {:profile-id (:id team-owner)
                                           :team-id (:id target-team)})
        file        (th/create-file* 1 {:profile-id (:id team-owner)
                                        :project-id (:id project)})
        organization-id      (uuid/next)]

    (with-organization-owner-access {:organization-owner-id (:id organization-owner)
                                     :organization-id organization-id
                                     :team-id (:id target-team)}
      (fn []
        ;; The team is not listed for a non-member, even though the organization
        ;; owner can access it directly.
        (let [out (th/command! {::th/type :get-teams
                                ::rpc/profile-id (:id organization-owner)})]
          (t/is (nil? (:error out)))
          (t/is (not-any? #(= (:id target-team) (:id %)) (:result out))))

        (let [out  (th/command! {::th/type :get-team
                                 ::rpc/profile-id (:id organization-owner)
                                 :id (:id target-team)})
              team (:result out)]
          (t/is (nil? (:error out)))
          (t/is (= (:id target-team) (:id team)))
          (t/is (false? (get-in team [:permissions :is-owner])))
          (t/is (false? (get-in team [:permissions :is-admin])))
          (t/is (false? (get-in team [:permissions :can-edit])))
          (t/is (= organization-id (get-in team [:organization :id])))
          (t/is (= "Acme" (get-in team [:organization :name]))))

        (let [out     (th/command! {::th/type :get-team-members
                                    ::rpc/profile-id (:id organization-owner)
                                    :team-id (:id target-team)})
              members (:result out)]
          (t/is (nil? (:error out)))
          (t/is (some #(= (:id team-owner) (:id %)) members))
          (t/is (not-any? #(= (:id organization-owner) (:id %)) members)))

        (let [out (th/command! {::th/type :get-projects
                                ::rpc/profile-id (:id organization-owner)
                                :team-id (:id target-team)})]
          (t/is (nil? (:error out)))
          (t/is (some #(= (:id project) (:id %)) (:result out))))

        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id organization-owner)
                                :id (:id file)})]
          (t/is (nil? (:error out)))
          (t/is (= (:id file) (get-in out [:result :id])))
          (t/is (false? (get-in out [:result :permissions :can-edit]))))

        (let [out   (th/command! {::th/type :rename-project
                                  ::rpc/profile-id (:id organization-owner)
                                  :id (:id project)
                                  :name "Nope"})
              error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :not-found)))))))

(t/deftest organization-owner-member-keeps-team-role
  (let [team-owner  (th/create-profile* 1)
        organization-owner   (th/create-profile* 2)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})
        organization-id      (uuid/next)]

    (th/create-team-role* {:team-id (:id target-team)
                           :profile-id (:id organization-owner)
                           :role :editor})

    (with-organization-owner-access {:organization-owner-id (:id organization-owner)
                                     :organization-id organization-id
                                     :team-id (:id target-team)}
      (fn []
        (let [out  (th/command! {::th/type :get-team
                                 ::rpc/profile-id (:id organization-owner)
                                 :id (:id target-team)})
              team (:result out)]
          (t/is (nil? (:error out)))
          (t/is (false? (get-in team [:permissions :is-owner])))
          (t/is (false? (get-in team [:permissions :is-admin])))
          (t/is (true? (get-in team [:permissions :can-edit]))))))))

(t/deftest removed-organization-owner-is-degraded-to-viewer
  (let [team-owner  (th/create-profile* 1)
        organization-owner   (th/create-profile* 2)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})
        organization-id      (uuid/next)
        messages    (atom [])]

    (th/create-team-role* {:team-id (:id target-team)
                           :profile-id (:id organization-owner)
                           :role :editor})

    (with-organization-owner-access {:organization-owner-id (:id organization-owner)
                                     :organization-id organization-id
                                     :team-id (:id target-team)}
      (fn []
        (let [out (with-captured-messages messages
                    #(th/command! {::th/type :delete-team-member
                                   ::rpc/profile-id (:id team-owner)
                                   :team-id (:id target-team)
                                   :member-id (:id organization-owner)}))]
          (t/is (nil? (:error out))))

        ;; The organization owner keeps read-only access, so they are notified
        ;; with a role change instead of being kicked out of the team.
        (let [notified (messages-for messages (:id organization-owner))]
          (t/is (= 1 (count notified)))
          (t/is (= :team-role-change (:type (first notified))))
          (t/is (= :viewer (:role (first notified))))
          (t/is (= (:id target-team) (:team-id (first notified))))
          (t/is (not-any? #(= :team-membership-change (:type %)) notified)))

        (let [out  (th/command! {::th/type :get-team
                                 ::rpc/profile-id (:id organization-owner)
                                 :id (:id target-team)})
              team (:result out)]
          (t/is (nil? (:error out)))
          (t/is (false? (get-in team [:permissions :is-owner])))
          (t/is (false? (get-in team [:permissions :is-admin])))
          (t/is (false? (get-in team [:permissions :can-edit]))))))))

(t/deftest removed-regular-member-is-still-kicked-out
  (let [team-owner  (th/create-profile* 1)
        organization-owner   (th/create-profile* 2)
        member      (th/create-profile* 3)
        target-team (th/create-team* 1 {:profile-id (:id team-owner)})
        organization-id      (uuid/next)
        messages    (atom [])]

    (th/create-team-role* {:team-id (:id target-team)
                           :profile-id (:id member)
                           :role :editor})

    (with-organization-owner-access {:organization-owner-id (:id organization-owner)
                                     :organization-id organization-id
                                     :team-id (:id target-team)}
      (fn []
        (let [out (with-captured-messages messages
                    #(th/command! {::th/type :delete-team-member
                                   ::rpc/profile-id (:id team-owner)
                                   :team-id (:id target-team)
                                   :member-id (:id member)}))]
          (t/is (nil? (:error out))))

        (let [notified (messages-for messages (:id member))]
          (t/is (= 1 (count notified)))
          (t/is (= :team-membership-change (:type (first notified))))
          (t/is (= :removed (:change (first notified))))
          (t/is (= (:id target-team) (:team-id (first notified)))))

        (let [out (th/command! {::th/type :get-team
                                ::rpc/profile-id (:id member)
                                :id (:id target-team)})]
          (t/is (th/ex-info? (:error out)))
          (t/is (th/ex-of-type? (:error out) :not-found)))))))
