;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-stats-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace :as dw]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

;; ---------------------------------------------------------------------------
;; Test compute-file-stats with various edge cases
;; ---------------------------------------------------------------------------

(t/deftest compute-file-stats-empty-file
  (t/testing "empty file with no pages"
    (let [file  (cthf/sample-file :file1 :page-label :page1)
          store (ths/setup-store file)
          state @store
          file-id (:id file)
          stats (dw/compute-file-stats state file-id)]
      (t/is (= (:num-pages stats) 1))
      (t/is (>= (:num-shapes stats) 0))
      (t/is (>= (:avg-shapes-per-page stats) 0))
      (t/is (>= (:max-shapes-per-page stats) 0))
      (t/is (>= (:num-components stats) 0))
      (t/is (>= (:num-linked-libraries stats) 0))
      (t/is (boolean? (:is-library stats)))
      (t/is (>= (:num-tokens stats) 0)))))

(t/deftest compute-file-stats-with-shapes
  (t/testing "file with shapes"
    (let [file  (-> (cthf/sample-file :file1 :page-label :page1)
                    (cthf/add-sample-shape :shape1)
                    (cthf/add-sample-shape :shape2))
          store (ths/setup-store file)
          state @store
          file-id (:id file)
          stats (dw/compute-file-stats state file-id)]
      (t/is (= (:num-pages stats) 1))
      (t/is (>= (:num-shapes stats) 2))
      (t/is (>= (:avg-shapes-per-page stats) 2))
      (t/is (>= (:max-shapes-per-page stats) 2)))))

(t/deftest compute-file-stats-no-tokens
  (t/testing "file with no tokens lib"
    (let [file  (cthf/sample-file :file1 :page-label :page1)
          store (ths/setup-store file)
          state @store
          file-id (:id file)
          stats (dw/compute-file-stats state file-id)]
      (t/is (= (:num-tokens stats) 0)))))

(t/deftest compute-file-stats-with-tokens
  (t/testing "file with tokens"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set {:name "global"
                                        :description "Global tokens"
                                        :tokens [{:name "color.primary"
                                                  :type :color
                                                  :value "#000000"}]}))
          file  (-> (cthf/sample-file :file1 :page-label :page1)
                    (assoc-in [:data :tokens-lib] tokens-lib))
          store (ths/setup-store file)
          state @store
          file-id (:id file)
          stats (dw/compute-file-stats state file-id)]
      (t/is (= (:num-tokens stats) 1)))))

(t/deftest compute-file-stats-multiple-pages
  (t/testing "file with multiple pages"
    (let [file  (-> (cthf/sample-file :file1 :page-label :page1)
                    (cthf/add-sample-page :page2)
                    (cthf/add-sample-page :page3))
          store (ths/setup-store file)
          state @store
          file-id (:id file)
          stats (dw/compute-file-stats state file-id)]
      (t/is (= (:num-pages stats) 3)))))
