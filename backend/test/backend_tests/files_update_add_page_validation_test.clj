;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.files-update-add-page-validation-test
  (:require
   [app.common.features :as cfeat]
   [app.common.files.validate :as val]
   [app.common.types.page :as ctp]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-update :as files-update]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- page-with-dangling-parent
  "A page whose only shape points at a parent that is not in the page, and
  that the page tree cannot reach from the root. It passes the file schema
  (geometry is valid) but fails the referential check."
  [page-id]
  (let [shape (cts/setup-shape
               {:id (uuid/next)
                :name "rect"
                :type :rect
                :parent-id (uuid/next)
                :frame-id uuid/zero})]
    (-> (ctp/make-empty-page {:id page-id :name "Page 2"})
        (assoc-in [:objects (:id shape)] shape))))

(defn- file-with
  [page]
  {:id (uuid/next)
   :features #{"components/v2"}
   :data {:pages [(:id page)]
          :pages-index {(:id page) page}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collection of the pages the affected check does not see
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest get-added-page-ids-collects-only-whole-page-adds
  (let [page-id (uuid/next)]
    (t/is (= [page-id]
             (@#'files-update/get-added-page-ids
              [{:type :add-page :page {:id page-id}}])))

    (t/testing "an editor-form add-page is left to the affected check"
      (t/is (empty? (@#'files-update/get-added-page-ids
                     [{:type :add-page :id page-id :name "Page 2"}]))))

    (t/testing "pages without a uuid id are ignored"
      (t/is (empty? (@#'files-update/get-added-page-ids
                     [{:type :add-page :page {:id "not-a-uuid"}}]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Soft validation of the collected pages
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest validate-added-pages-reports-a-dangling-parent
  (let [page-id (uuid/next)
        page    (page-with-dangling-parent page-id)
        errors  (@#'files-update/validate-added-pages
                 (file-with page)
                 {}
                 [{:type :add-page :page page}])]
    (t/is (seq errors))
    (t/is (contains? (set (map :code errors)) :parent-not-found))
    (t/is (= #{page-id} (set (map :page-id errors))))))

(t/deftest validate-added-pages-skips-editor-form-adds
  (let [page-id (uuid/next)
        page    (page-with-dangling-parent page-id)]
    (t/is (nil? (@#'files-update/validate-added-pages
                 (file-with page)
                 {}
                 [{:type :add-page :id page-id :name "Page 2"}])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; update-file accepts the page and reports the error
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest update-file-accepts-page-added-with-a-whole-page-value
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        page-id  (uuid/next)
        page     (page-with-dangling-parent page-id)
        calls    (atom [])
        validate val/validate-file-affected]

    (with-redefs [cf/flags (conj cf/flags :file-validation)
                  val/validate-file-affected
                  (fn [file libs changes]
                    (let [errors (validate file libs changes)]
                      (swap! calls conj [changes errors])
                      errors))]

      (let [out (th/command! {::th/type :update-file
                              ::rpc/profile-id (:id profile)
                              :id (:id file)
                              :session-id (uuid/next)
                              :revn 0
                              :vern 0
                              :features cfeat/supported-features
                              :changes [{:type :add-page :page page}]})]

        (t/testing "the save is accepted"
          (t/is (nil? (:error out))))

        (t/testing "the page is stored"
          (let [out (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id (:id file)})]
            (t/is (nil? (:error out)))
            (t/is (some? (get-in out [:result :data :pages-index page-id])))))

        (t/testing "the added page is checked in soft mode"
          (let [[changes errors]
                (some (fn [[changes :as entry]]
                        (when (= [{:type :add-page :id page-id}] changes)
                          entry))
                      @calls)]
            (t/is (some? changes))
            (t/is (contains? (set (map :code errors)) :parent-not-found))
            (t/is (= #{page-id} (set (map :page-id errors))))))))))
