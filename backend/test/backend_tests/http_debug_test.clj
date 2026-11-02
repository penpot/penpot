;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-debug-test
  "The /dbg validate action walks the whole file and its libraries: it
  must hand `get-resolved-file-libraries` the file (not its id) and
  resolve the pointer-mapped pages with the file's own loader."
  (:require
   [app.http.debug :as debug]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest validate-file-action-resolves-pointer-mapped-data
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        handler (deref #'debug/validate-file)
        result  (handler th/*system* {:params {:file-id (str (:id file))}})]

    (t/is (= 200 (::yres/status result)))
    (t/is (= "NO VALIDATION ERRORS FOUND" (::yres/body result)))))

(t/deftest debug-download-does-not-expose-the-storage-object-id
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        sobject (sto/put-object! (::sto/storage th/*system*)
                                 {::sto/content (sto/content "sobject")
                                  :content-type "application/octet-stream"
                                  :bucket "file-data"
                                  :file-id (:id file)
                                  :id (:id file)})
        _       (th/db-update! :file-data {:storage-object-id (:id sobject)}
                               {:file-id (:id file)
                                :id (:id file)})
        handler (deref #'debug/download-file-data)
        result  (handler th/*system* {:params {:file-id (str (:id file))
                                               :download true}})
        body    (String. ^bytes (::yres/body result))]

    (t/is (= 200 (::yres/status result)))
    (t/is (str/includes? (get-in result [::yres/headers "content-disposition"])
                         "attachment"))
    (t/is (not (str/includes? body "storage-object-id")))
    (t/is (not (str/includes? body "storage_object_id")))))
