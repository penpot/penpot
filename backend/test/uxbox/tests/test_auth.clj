;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tests.test-auth
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [buddy.hashers :as hashers]
   [uxbox.db :as db]
   [uxbox.services.core :as sv]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-failed-auth
  (let [user @(th/create-user db/pool 1)
        event {:username "user1"
               :type :login
               :password "foobar"
               :metadata "1"
               :scope "foobar"}
        [err res] (th/try-on
                   (sv/mutation event))]
    (t/is (nil? res))
    (t/is (= (:type err) :validation))
    (t/is (= (:code err) :uxbox.services.auth/wrong-credentials))))

(t/deftest test-success-auth
  (let [user @(th/create-user db/pool 1)
        event {:username "user1"
               :type :login
               :password "123123"
               :metadata "1"
               :scope "foobar"}
        [err res] (th/try-on
                   (sv/mutation event))]
    (t/is (= res (:id user)))
    (t/is (nil? err))))
