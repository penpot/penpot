;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.branch-file-queries-test
  "Regression tests for the queries that count or list a team's files: a
  branch file is a hidden copy of its main file, so none of them may
  count it, list it, or copy it as an ordinary file."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest counts-and-listings-skip-branch-files
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          team-id   (:default-team-id profile)
          proj-id   (:default-project-id profile)
          library   (th/create-file* 1 {:profile-id (:id profile)
                                        :project-id proj-id
                                        :is-shared true})
          main      (th/create-file* 2 {:profile-id (:id profile)
                                        :project-id proj-id
                                        :is-shared false})
          _         (th/link-file-to-library* {:file-id (:id main) :library-id (:id library)})
          out       (th/command! {::th/type :create-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :file-id (:id main)
                                  :name "hidden"})
          branch-id (-> out :result :branch-file-id)]

      (t/is (nil? (:error out)))
      ;; the branch copied main's link to the library
      (t/is (seq (th/db-query :file-library-rel {:file-id branch-id
                                                 :library-file-id (:id library)})))

      (t/testing "the project counts the library and main only"
        (let [out  (th/command! {::th/type :get-projects
                                 ::rpc/profile-id (:id profile)
                                 :team-id team-id})
              proj (d/seek #(= proj-id (:id %)) (:result out))]
          (t/is (nil? (:error out)))
          (t/is (= 2 (:count proj)))
          (t/is (= 2 (:total-count proj)))))

      (t/testing "the team stats count the library and main only"
        (let [out (th/command! {::th/type :get-team-stats
                                ::rpc/profile-id (:id profile)
                                :team-id team-id})]
          (t/is (nil? (:error out)))
          (t/is (= 2 (-> out :result :files)))))

      (t/testing "the unpublish dialog lists main only"
        (let [out (th/command! {::th/type :get-library-file-references
                                ::rpc/profile-id (:id profile)
                                :file-id (:id library)})]
          (t/is (nil? (:error out)))
          (t/is (= [(:id main)] (mapv :id (:result out))))))

      (t/testing "a team duplication copies the library and main only"
        (t/is (= #{(:id library) (:id main)}
                 (db/run! th/*system* bfc/get-team-files-ids team-id)))))))
