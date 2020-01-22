;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tests.test-services-auth
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest failed-auth
  (let [user @(th/create-user db/pool 1)
        event {:username "user1"
               ::sm/type :login
               :password "foobar"
               :metadata "1"
               :scope "foobar"}
        out (th/try-on! (sm/handle event))]
    ;; (th/print-result! out)
    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :service-error)))

    (let [error (ex-cause (:error out))]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :uxbox.services.mutations.auth/wrong-credentials)))))

(t/deftest success-auth
  (let [user @(th/create-user db/pool 1)
        event {:username "user1"
               ::sm/type :login
               :password "123123"
               :metadata "1"
               :scope "foobar"}
        out (th/try-on! (sm/handle event))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (get-in out [:result :id]) (:id user)))))
