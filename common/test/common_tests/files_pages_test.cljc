;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-pages-test
  "Regression tests for the nil-page writer
  (see https://github.com/penpot/penpot/issues/12433).

  Production files turned up with a nil entry in `:pages` and a nil
  key in `:pages-index` (the shape legacy-57 cleans). The writer was
  `:add-page` with a page missing a valid `:id`: the change schema
  leaves `:page` unchecked (`:any`) and nothing else stopped it, so a
  nil page was persisted whenever validation was skipped or soft.
  `process-change :add-page` now asserts a valid page id up front,
  like `:del-page` already did."
  (:require
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- schema-status
  [file]
  (try
    (cfv/validate-file-schema! file)
    :valid
    (catch #?(:clj Exception :cljs js/Error) e
      [:invalid (ex-message e)])))

(t/deftest add-page-without-id-is-rejected
  (let [file (thf/sample-file :file)]
    (try
      (thf/apply-changes file
                         {:redo-changes [{:type :add-page :page {:name "Ghost"}}]}
                         :validate? false)
      (t/is false "expected exception to be thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (let [data (ex-data e)]
          (t/is (= :validation (:type data)))
          (t/is (= "add-page requires a page with a valid :id" (ex-message e))))))))

(t/deftest mov-page-with-nil-id-is-rejected-at-change-level
  (let [file (thf/sample-file :file)]
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (thf/apply-changes file
                                      {:redo-changes [{:type :mov-page :id nil :index 0}]}
                                      :validate? false))
          "mov-page with nil id never reaches the file")))

(t/deftest del-missing-page-is-a-no-op
  (let [file  (thf/sample-file :file)
        file' (thf/apply-changes file
                                 {:redo-changes [{:type :del-page :id (uuid/next)}]}
                                 :validate? false)
        data  (:data file')]
    (t/is (not-any? nil? (:pages data)) "no nil page refs")
    (t/is (not (contains? (:pages-index data) nil)) "no nil page keys")
    (t/is (= :valid (schema-status file')) "stays schema-valid")))

(t/deftest mov-page-out-of-range-keeps-valid
  (let [file  (thf/sample-file :file)
        page  (thf/current-page file)
        file' (thf/apply-changes file
                                 {:redo-changes [{:type :mov-page :id (:id page) :index 99}]}
                                 :validate? false)
        data  (:data file')]
    (t/is (not-any? nil? (:pages data)) "no nil page refs")
    (t/is (= :valid (schema-status file')) "stays schema-valid")))

(t/deftest add-page-roundtrip-stays-valid
  (let [file    (thf/sample-file :file)
        page-id (uuid/next)
        file'   (thf/apply-changes file
                                   {:redo-changes [{:type :add-page
                                                    :id page-id
                                                    :name "Second"}]})]
    (t/is (some #(= page-id %) (:pages (:data file'))) "page ref stored")
    (t/is (some? (get-in file' [:data :pages-index page-id])) "page stored")
    (t/is (= :valid (schema-status file')) "stays schema-valid")))

(t/deftest add-page-with-mismatched-id-is-rejected
  (let [file (thf/sample-file :file)]
    (try
      (thf/apply-changes file
                         {:redo-changes [{:type :add-page
                                          :id (uuid/next)
                                          :page {:id (uuid/next) :name "Ghost"}}]}
                         :validate? false)
      (t/is false "expected exception to be thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (let [data (ex-data e)]
          (t/is (= :conflict (:type data)))
          (t/is (= "id and page should agree" (ex-message e))))))))
