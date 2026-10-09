;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.srepl-main-test
  (:require
   [app.srepl.main :as srepl]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest parse-emails
  (t/is (= ["some@example.com"]
           (srepl/parse-emails "some@example.com")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails "some@example.com,other@example.com")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails " some@example.com , other@example.com ,")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails ["some@example.com" "other@example.com"])))

  (t/is (= [] (srepl/parse-emails ",")))

  (t/is (thrown? clojure.lang.ExceptionInfo
                 (srepl/parse-emails 42))))

(t/deftest delete-profile-emits-identifiable-audit-event
  ;; The SREPL delete used to emit no profile-id and empty props, leaving a
  ;; row no projection could attribute. Both insert and cascade are mocked,
  ;; so this has no side effects.
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}
               wrk-mock {:target 'app.worker/invoke! :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})]
      (srepl/delete-profile! (str (:id profile)))
      (let [events (->> (:call-args-list @audit-mock)
                        (map second))]
        (t/is (= 1 (count events)))
        (let [event (first events)]
          (t/is (= "delete-profile" (:name event)))
          (t/is (= "action" (:type event)))
          (t/is (= (:id profile) (:profile-id event)))
          (t/is (= (:id profile) (get-in event [:props :id]))))))))

(t/deftest delete-team-emits-identifiable-audit-event
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}
               wrk-mock {:target 'app.worker/invoke! :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          team    (th/create-team* 95 {:profile-id (:id profile)})]
      (srepl/delete-team! (str (:id team)))
      (let [events (->> (:call-args-list @audit-mock)
                        (map second))]
        (t/is (= 1 (count events)))
        (let [event (first events)]
          (t/is (= "delete-team" (:name event)))
          (t/is (= "action" (:type event)))
          (t/is (= (:id team) (get-in event [:props :id])))
          (t/is (= "explicit call to delete-team!"
                   (get-in event [:context :cause]))))))))
