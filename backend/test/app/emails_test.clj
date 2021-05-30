;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.emails-test
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [app.db :as db]
   [app.emails :as emails]
   [app.test-helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest register-email-rendering
  (let [result (emails/render emails/register {:to "example@app.io" :name "foo"})]
    (t/is (map? result))
    (t/is (contains? result :subject))
    (t/is (contains? result :body))
    (t/is (contains? result :to))
    #_(t/is (contains? result :reply-to))
    (t/is (vector? (:body result)))))
