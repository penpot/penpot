;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.setup-test
  (:require
   [app.db :as db]
   [app.db :as-alias db]
   [app.loggers.audit :as audit]
   [app.setup :as setup]
   [app.setup.keys :as keys]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest secret-key-is-required
  (t/testing "a missing key is rejected with the environment variable names"
    (let [err (try
                (#'setup/require-secret-key nil)
                nil
                (catch Exception e e))
          edata (ex-data err)]
      (t/is (some? err))
      (t/is (= :missing-secret-key (:code edata)))
      (t/is (= "PENPOT_SECRET_KEY or PENPOT_SECRET_KEY_FILE is required"
               (:hint edata)))))
  (t/testing "a blank key is rejected"
    (let [err (try
                (#'setup/require-secret-key "  \n\t")
                nil
                (catch Exception e e))]
      (t/is (some? err))))
  (t/testing "a configured key is returned unchanged"
    (t/is (= "configured" (#'setup/require-secret-key "configured")))))

(t/deftest props-derive-the-token-key-from-the-configured-secret
  (with-redefs [audit/submit (constantly nil)]
    (let [props (ig/init-key :app.setup/props
                             {::db/pool            th/*pool*
                              :app.setup/key      "configured-secret"})]
      (t/testing "the configured secret is used verbatim"
        (t/is (= "configured-secret" (:secret-key props))))
      (t/testing "the token key derives from the configured secret"
        (t/is (= (vec (keys/derive "configured-secret" :salt "tokens"))
                 (vec (:tokens-key props)))))
      (t/testing "the instance id is initialized"
        (t/is (some? (:instance-id props)))))))
