;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.logical-deletion-test
  (:require
   [app.common.time :as ct]
   [app.config :as cf]
   [app.features.logical-deletion :as ldel]
   [clojure.test :as t]))

(t/deftest get-deletion-delay-for-active-subscriptions
  (t/is (= (ct/duration {:days 30})
           (ldel/get-deletion-delay {:subscription {:type "unlimited"
                                                    :status "active"}})))

  (t/is (= (ct/duration {:days 90})
           (ldel/get-deletion-delay {:subscription {:type "enterprise"
                                                    :status "active"}})))

  (t/is (= (ct/duration {:days 90})
           (ldel/get-deletion-delay {:subscription {:type "nitrate"
                                                    :status "active"}}))))

(t/deftest get-deletion-delay-for-canceled-subscriptions
  (let [fallback (ct/duration {:days 5})]
    (with-redefs [cf/get-deletion-delay (fn [] fallback)]
      (t/is (= fallback
               (ldel/get-deletion-delay {:subscription {:type "nitrate"
                                                        :status "canceled"}})))

      (t/is (= fallback
               (ldel/get-deletion-delay {:subscription {:type "enterprise"
                                                        :status "unpaid"}}))))))
