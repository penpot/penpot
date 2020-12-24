;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-services-files
  (:require
   [clojure.test :as t]
   [datoteka.core :as fs]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.tests.helpers :as th]
   [app.util.storage :as ust]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest files-crud
  (let [prof (th/create-profile th/*pool* 1)
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        file-id (uuid/next)
        page-id (uuid/next)]

    (t/testing "create file"
      (let [data {::th/type :create-file
                  :profile-id (:id prof)
                  :project-id proj-id
                  :id file-id
                  :name "foobar"
                  :is-shared false}
            out (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:name data) (:name result)))
          (t/is (= proj-id (:project-id result))))))

    (t/testing "rename file"
      (let [data {::th/type :rename-file
                  :id file-id
                  :name "new name"
                  :profile-id (:id prof)}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "query files"
      (let [data {::th/type :files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= file-id (get-in result [0 :id])))
          (t/is (= "new name" (get-in result [0 :name])))
          (t/is (= 1 (count (get-in result [0 :data :pages])))))))

    (t/testing "query single file without users"
      (let [data {::th/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= file-id (:id result)))
          (t/is (= "new name" (:name result)))
          (t/is (= 1 (count (get-in result [:data :pages]))))
          (t/is (nil? (:users result))))))

    (t/testing "delete file"
      (let [data {::th/type :delete-file
                  :id file-id
                  :profile-id (:id prof)}
            out (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query single file after delete"
      (let [data {::th/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out (th/query! data)]

        ;; (th/print-result! out)

        (let [error      (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found)))))

    (t/testing "query list files after delete"
      (let [data {::th/type :files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))
