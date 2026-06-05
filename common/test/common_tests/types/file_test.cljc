;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.file-test
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.file :as ctf]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.token-status :as ctos]
   [clojure.test :as t]))

(t/deftest test-ensure-tokens-lib
  (t/testing "ensure-tokens-lib should add a tokens-lib and token-status to the file data if they are missing, and should not modify them if they already exist"
    (let [file       (thf/sample-file :file1)
          file-data  (ctf/file-data file)
          file-data' (ctf/ensure-tokens-lib file-data)]
      (t/is (contains? file-data' :tokens-lib))
      (t/is (ctob/tokens-lib? (:tokens-lib file-data')))
      (t/is (contains? file-data' :token-status))
      (t/is (ctos/token-status? (:token-status file-data')))))

  (t/testing "ensure-tokens-lib should create a token-status from an existing tokens-lib")
  (let [file (thf/sample-file :file1)
        file-data (-> (ctf/file-data file)
                      (assoc :tokens-lib (-> (ctob/make-tokens-lib)
                                             (ctob/add-set (ctob/make-token-set
                                                            :id (thi/new-id! :set1)
                                                            :name "set1"))
                                             (ctob/add-set (ctob/make-token-set
                                                            :id (thi/new-id! :set2)
                                                            :name "set2"))
                                             (ctob/add-theme (ctob/make-token-theme
                                                              :id (thi/new-id! :theme1)
                                                              :name "theme1"
                                                              :sets #{"set1"}))
                                             (ctob/add-theme (ctob/make-token-theme
                                                              :id ctob/hidden-theme-id
                                                              :name "HIDDEN_THEME"
                                                              :sets #{"set2"}))
                                             (ctob/activate-theme ctob/hidden-theme-id)
                                             (ctob/activate-theme (thi/id :theme1)))))
        file-data' (ctf/ensure-tokens-lib file-data)
        token-status' (:token-status file-data')]

    (t/is (contains? file-data' :tokens-lib))
    (t/is (ctob/tokens-lib? (:tokens-lib file-data')))
    (t/is (contains? file-data' :token-status))
    (t/is (ctos/token-status? token-status'))
    (t/is (ctos/check-token-status token-status'))
    (t/is (= 1 (ctos/active-theme-count token-status')))
    (t/is (true? (ctos/theme-active? token-status' (thi/id :theme1))))
    (t/is (= 1 (ctos/active-set-count token-status') 1))
    (t/is (ctos/set-active? token-status' (thi/id :set1)))))

