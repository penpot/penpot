;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-viewer-test
  (:require
   [backend-tests.helpers :as th]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [clojure.test :as t]
   [datoteka.core :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest retrieve-bundle
  (let [prof     (th/create-profile* 1 {:is-active true})
        prof2    (th/create-profile* 2 {:is-active true})
        team-id  (:default-team-id prof)
        proj-id  (:default-project-id prof)

        file     (th/create-file* 1 {:profile-id (:id prof)
                                     :project-id proj-id
                                     :is-shared false})
        share-id (atom nil)]

    (t/testing "authenticated with page-id"
      (let [data {::th/type :view-only-bundle
                  :profile-id (:id prof)
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}

            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (contains? result :share-links))
          (t/is (contains? result :permissions))
          (t/is (contains? result :libraries))
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    (t/testing "generate share token"
      (let [data {::th/type :create-share-link
                  :profile-id (:id prof)
                  :file-id (:id file)
                  :pages #{(get-in file [:data :pages 0])}
                  :who-comment "team"
                  :who-inspect "all"}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (uuid? (:id result)))
          (reset! share-id (:id result)))))

    (t/testing "not authenticated with page-id"
      (let [data {::th/type :view-only-bundle
                  :profile-id (:id prof2)
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (let [error      (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found))
          (t/is (= (:code error-data) :object-not-found)))))

    (t/testing "authenticated with token & profile"
      (let [data {::th/type :view-only-bundle
                  :profile-id (:id prof2)
                  :share-id @share-id
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    (t/testing "authenticated with token"
      (let [data {::th/type :view-only-bundle
                  :share-id @share-id
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    ))
