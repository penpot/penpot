;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.file-test
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.file :as ctf]
   [app.common.types.token-status :as ctos]
   [app.common.types.tokens-lib :as ctob]
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

  (t/testing "ensure-tokens-lib should create a token-status from an existing tokens-lib"
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
      (t/is (= 1 (ctos/active-themes-count token-status')))
      (t/is (true? (ctos/theme-active? token-status' (thi/id :theme1))))
      (t/is (= 1 (ctos/active-set-count token-status') 1))
      (t/is (ctos/set-active? token-status' (thi/id :set1))))))

(t/deftest test-update-tokens-lib
  (t/testing "update when there is no tokens-lib has no effect"
    (let [file (thf/sample-file :file1)
          file' (ctf/update-tokens-lib file #(t/is false "This should not be called"))]
      (t/is (= file file'))))
  
  (t/testing "update a tokens-lib applies the changes correctly"
    (let [file (-> (thf/sample-file :file1)
                   (tht/add-tokens-lib))
          file' (ctf/update-file-data file
                                      #(ctf/update-tokens-lib
                                        %
                                        ctob/add-theme
                                        (ctob/make-token-theme :id (thi/new-id! :theme1)
                                                               :name "theme 1")))

          tokens-lib' (tht/get-tokens-lib file')]
      (t/is (= 2 (ctob/theme-count tokens-lib')))  ;; Count the hidden theme
      (t/is (ctob/token-theme? (ctob/get-theme tokens-lib' (thi/id :theme1)))))))

(t/deftest test-update-token-status
  (t/testing "update when there is no token-status has no effect"
    (let [file (thf/sample-file :file1)
          file' (ctf/update-token-status file #(t/is false "This should not be called"))]
      (t/is (= file file'))))

  (t/testing "update a token-status applies the changes correctly"
    (let [file (-> (thf/sample-file :file1)
                   (tht/add-tokens-lib)
                   (tht/update-tokens-lib
                    (fn [tokens-lib]
                      (-> tokens-lib
                          (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme1) :name "theme"))))))
          file' (ctf/update-file-data file
                                      #(ctf/update-token-status
                                        %
                                        ctos/activate-theme
                                        (tht/get-tokens-lib file)
                                        (thi/id :theme1)))
          token-status' (tht/get-token-status file')]
      (t/is (= 1 (ctos/active-themes-count token-status')))
      (t/is (ctos/theme-active? token-status' (thi/id :theme1))))))