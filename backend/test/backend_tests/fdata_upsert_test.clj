;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.fdata-upsert-test
  "Covers the write contract of `fdata/upsert!` after the `legacy-db` backend
  became read-only."

  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.features.fdata :as fdata]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- upsert-cause
  [params]
  (try
    (fdata/upsert! th/*system* params)
    nil
    (catch Exception cause
      cause)))

(t/deftest legacy-db-param-is-rejected
  ;; `legacy-db` is not a write backend anymore: the schema refuses it before
  ;; touching the database.
  (let [cause (upsert-cause {:id (uuid/next)
                             :file-id (uuid/next)
                             :type "snapshot"
                             :backend "legacy-db"
                             :data (byte-array [1 2 3])})]
    (t/is (some? cause))
    (t/is (= :assertion (:type (ex-data cause))))
    (t/is (= :data-validation (:code (ex-data cause))))))

(t/deftest legacy-db-config-is-rejected-as-read-only
  ;; The configuration value is kept for reading old data, but a write with
  ;; it as the default backend fails with a clear message.
  (with-redefs [cf/config (assoc cf/config :file-data-backend "legacy-db")]
    (let [cause (upsert-cause {:id (uuid/next)
                               :file-id (uuid/next)
                               :type "snapshot"
                               :data (byte-array [1 2 3])})]
      (t/is (some? cause))
      (t/is (= :validation (:type (ex-data cause))))
      (t/is (= :legacy-backend-read-only (:code (ex-data cause)))))))

(t/deftest file-data-backend-defaults-to-db
  (t/is (= "db" (cf/get :file-data-backend))))
