;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.services-teams-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [app.test-helpers :as th]
   [app.util.time :as dt]
   [clojure.test :as t]
   [datoteka.core :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-invite-team-member
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})
          profile3 (th/create-profile* 3 {:is-active true :is-muted true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})

          pool     (:app.db/pool th/*system*)
          data     {::th/type :invite-team-member
                    :team-id (:id team)
                    :role :editor
                    :profile-id (:id profile1)}]

      ;; invite external user without complaints
      (let [data (assoc data :email "foo@bar.com")
            out  (th/mutation! data)]

        ;; (th/print-result! out)

        (t/is (nil? (:result out)))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; invite internal user without complaints
      (th/reset-mock! mock)
      (let [data (assoc data :email (:email profile2))
            out  (th/mutation! data)]
        (t/is (nil? (:result out)))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; invite user with complaint
      (th/create-global-complaint-for pool {:type :complaint :email "foo@bar.com"})
      (th/reset-mock! mock)
      (let [data (assoc data :email "foo@bar.com")
            out  (th/mutation! data)]
        (t/is (nil? (:result out)))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; invite user with bounce
      (th/reset-mock! mock)
      (th/create-global-complaint-for pool {:type :bounce :email "foo@bar.com"})
      (let [data  (assoc data :email "foo@bar.com")
            out   (th/mutation! data)
            error (:error out)]

        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :email-has-permanent-bounces))
        (t/is (= 0 (:call-count (deref mock)))))

      ;; invite internal user that is muted
      (th/reset-mock! mock)
      (let [data  (assoc data :email (:email profile3))
            out   (th/mutation! data)
            error (:error out)]

        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :member-is-muted))
        (t/is (= 0 (:call-count (deref mock)))))

      )))

(t/deftest test-deletion
  (let [task     (:app.tasks.objects-gc/handler th/*system*)
        profile1 (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile1)})
        pool     (:app.db/pool th/*system*)
        data     {::th/type :delete-team
                  :team-id (:id team)
                  :profile-id (:id profile1)}]

    ;; team is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (task {:max-age (dt/duration 0)})]
      (t/is (nil? result)))

    ;; query the list of teams
    (let [data {::th/type :teams
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 2 (count result)))
        (t/is (= (:id team) (get-in result [1 :id])))
        (t/is (= (:default-team-id profile1) (get-in result [0 :id])))))

    ;; Request team to be deleted
    (let [params {::th/type :delete-team
                  :id (:id team)
                  :profile-id (:id profile1)}
          out    (th/mutation! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out))))

    ;; query the list of teams after soft deletion
    (let [data {::th/type :teams
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 1 (count result)))
        (t/is (= (:default-team-id profile1) (get-in result [0 :id])))))

    ;; run permanent deletion (should be noop)
    (let [result (task {:max-age (dt/duration {:minutes 1})})]
      (t/is (nil? result)))

    ;; query the list of projects after hard deletion
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))

    ;; run permanent deletion
    (let [result (task {:max-age (dt/duration 0)})]
      (t/is (nil? result)))

    ;; query the list of projects of a after hard deletion
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))
    ))





