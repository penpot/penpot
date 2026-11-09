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
   [backend-tests.helpers :as th]
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
