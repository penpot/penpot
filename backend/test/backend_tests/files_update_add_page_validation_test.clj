;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.files-update-add-page-validation-test
  (:require
   [app.common.features :as cfeat]
   [app.common.types.page :as ctp]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

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

(defn- add-page-forms
  "The forms of `:add-page` that carry a whole page: the editor sends the
  page with its id at the top level (duplicate page, undo of a page
  deletion), `files/builder.cljc` sends the page alone, and the change
  schema also admits a top-level id that differs from the page's."
  [page]
  {"editor form" {:type :add-page :id (:id page) :page page}
   "page-only form" {:type :add-page :page page}
   "differing ids" {:type :add-page :id (uuid/next) :page page}})

(defn- add-page!
  "Sends `change` alone, or as one commit group when `commit-id` is given,
  which is how the editor sends its changes."
  ([profile file change]
   (add-page! profile file change nil))
  ([profile file change commit-id]
   (th/command! (cond-> {::th/type :update-file
                         ::rpc/profile-id (:id profile)
                         :id (:id file)
                         :session-id (uuid/next)
                         :revn 0
                         :vern 0
                         :features cfeat/supported-features}
                  (nil? commit-id)
                  (assoc :changes [change])

                  (some? commit-id)
                  (assoc :changes-with-metadata
                         [{:commit-id commit-id :changes [change]}])))))

(defn- stored-page
  [profile file page-id]
  (-> (th/command! {::th/type :get-file
                    ::rpc/profile-id (:id profile)
                    :id (:id file)})
      (get-in [:result :data :pages-index page-id])))

(t/deftest update-file-checks-a-whole-page-add-page-as-the-flags-say
  ;; The page goes through the same affected check as every other page a
  ;; save touches, so the deployment's validation flags decide whether an
  ;; invalid page is refused or only logged.
  (doseq [[mode flags refused?]
          [["hard" (conj cf/flags :file-validation) true]
           ["soft" (-> cf/flags (disj :file-validation) (conj :soft-file-validation)) false]]
          [form change] (add-page-forms (page-with-dangling-parent (uuid/next)))]
    (t/testing (str mode ", " form)
      (th/database-reset
       (fn []
         (let [profile (th/create-profile* 1 {:is-active true})
               file    (th/create-file* 1 {:profile-id (:id profile)
                                           :project-id (:default-project-id profile)})
               page-id (get-in change [:page :id])]
           (with-redefs [cf/flags flags]
             (let [out (add-page! profile file change)]
               (if refused?
                 (do
                   (t/is (th/ex-of-code? (:error out) :referential-integrity))
                   (t/is (nil? (stored-page profile file page-id))))
                 (do
                   (t/is (nil? (:error out)))
                   (t/is (some? (stored-page profile file page-id)))))))))))))

(t/deftest update-file-rejects-the-commit-of-an-invalid-whole-page-add-page
  ;; A save that names its commits is not refused whole: the answer lists
  ;; the rejected commit and the page is not stored.
  (let [profile   (th/create-profile* 1 {:is-active true})
        file      (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})
        page-id   (uuid/next)
        change    {:type :add-page :page (page-with-dangling-parent page-id)}
        commit-id (uuid/next)]
    (with-redefs [cf/flags (conj cf/flags :file-validation)]
      (let [out      (add-page! profile file change commit-id)
            rejected (get-in out [:result :rejected])]
        (t/is (nil? (:error out)))
        (t/is (= commit-id (:failed-commit-id rejected)))
        (t/is (= :referential-integrity (get-in rejected [:error :code])))
        (t/is (nil? (stored-page profile file page-id)))))))
