;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 app Labs SL

(ns app.tests.test-services-files
  (:require
   [clojure.test :as t]
   [datoteka.core :as fs]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.services.mutations :as sm]
   [app.services.queries :as sq]
   [app.tests.helpers :as th]
   [app.util.storage :as ust]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest files-crud
  (let [prof (th/create-profile db/pool 1)
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        file-id (uuid/next)
        page-id (uuid/next)]

    (t/testing "create file"
      (let [data {::sm/type :create-file
                  :profile-id (:id prof)
                  :project-id proj-id
                  :id file-id
                  :is-shared false
                  :name "test file"}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:name data) (:name result)))
          (t/is (= proj-id (:project-id result))))))

    (t/testing "rename file"
      (let [data {::sm/type :rename-file
                  :id file-id
                  :name "new name"
                  :profile-id (:id prof)}
            out  (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "query files"
      (let [data {::sq/type :files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= file-id (get-in result [0 :id])))
          (t/is (= "new name" (get-in result [0 :name])))
          (t/is (= 1 (count (get-in result [0 :pages])))))))

    (t/testing "query single file without users"
      (let [data {::sq/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= file-id (:id result)))
          (t/is (= "new name" (:name result)))
          (t/is (vector? (:pages result)))
          (t/is (= 1 (count (:pages result))))
          (t/is (nil? (:users result))))))

    (t/testing "delete file"
      (let [data {::sm/type :delete-file
                  :id file-id
                  :profile-id (:id prof)}
            out (th/try-on! (sm/handle data))]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query single file after delete"
      (let [data {::sq/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)

        (let [error (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :service-error))
          (t/is (= (:name error-data) :app.services.queries.files/file)))

        (let [error (ex-cause (:error out))
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found)))))

    (t/testing "query list files after delete"
      (let [data {::sq/type :files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))

;; TODO: delete file image
