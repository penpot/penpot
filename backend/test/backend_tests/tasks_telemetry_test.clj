;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.tasks-telemetry-test
  (:require
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-base-report-data-structure
  (with-mocks [mock {:target 'app.tasks.telemetry/send!
                     :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true
                                      :props {:newsletter-news true}})]

      (th/run-task! :telemetry {:send? true :enabled? true})

      (t/is (:called? @mock))
      (let [[_ data] (-> @mock :call-args)]
        (t/is (contains? data :subscriptions))
        (t/is (= [(:email prof)] (get-in data [:subscriptions :newsletter-news])))
        (t/is (contains? data :total-fonts))
        (t/is (contains? data :total-users))
        (t/is (contains? data :total-projects))
        (t/is (contains? data :total-files))
        (t/is (contains? data :total-teams))
        (t/is (contains? data :total-comments))
        (t/is (contains? data :instance-id))
        (t/is (contains? data :jvm-cpus))
        (t/is (contains? data :jvm-heap-max))
        (t/is (contains? data :max-users-on-team))
        (t/is (contains? data :avg-users-on-team))
        (t/is (contains? data :max-files-on-project))
        (t/is (contains? data :avg-files-on-project))
        (t/is (contains? data :max-projects-on-team))
        (t/is (contains? data :avg-files-on-project))
        (t/is (contains? data :version))))))
