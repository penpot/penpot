;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.tests.test-emails
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [mockery.core :refer [with-mock]]
   [app.db :as db]
   [app.emails :as emails]
   [app.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest register-email-rendering
  (let [result (emails/render emails/register {:to "example@app.io" :name "foo"})]
    (t/is (map? result))
    (t/is (contains? result :subject))
    (t/is (contains? result :content))
    (t/is (contains? result :to))
    (t/is (contains? result :reply-to))
    (t/is (vector? (:content result)))))

;; (t/deftest email-sending-and-sendmail-job
;;   (let [res @(emails/send! emails/register {:to "example@app.io" :name "foo"})]
;;     (t/is (nil? res)))
;;   (with-mock mock
;;     {:target 'app.jobs.sendmail/impl-sendmail
;;      :return (p/resolved nil)}

;;     (let [res @(app.jobs.sendmail/send-emails {})]
;;       (t/is (= 1 res))
;;       (t/is (:called? @mock))
;;       (t/is (= 1 (:call-count @mock))))

;;     (let [res @(app.jobs.sendmail/send-emails {})]
;;       (t/is (= 0 res))
;;       (t/is (:called? @mock))
;;       (t/is (= 1 (:call-count @mock))))))

