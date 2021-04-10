;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tests.test-services-viewer
  (:require
   [clojure.test :as t]
   [datoteka.core :as fs]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest retrieve-bundle
  (let [prof    (th/create-profile* 1 {:is-active true})
        prof2   (th/create-profile* 2 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)

        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id proj-id
                                    :is-shared false})
        token   (atom nil)]

    (t/testing "authenticated with page-id"
      (let [data {::th/type :viewer-bundle
                  :profile-id (:id prof)
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])}

            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (contains? result :token))
          (t/is (contains? result :page))
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    (t/testing "generate share token"
      (let [data {::th/type :create-file-share-token
                  :profile-id (:id prof)
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (string? (:token result)))
          (reset! token (:token result)))))

    (t/testing "not authenticated with page-id"
      (let [data {::th/type :viewer-bundle
                  :profile-id (:id prof2)
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (let [error (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found))
          (t/is (= (:code error-data) :object-not-found)))))

    ;; (t/testing "authenticated with token & profile"
    ;;   (let [data {::sq/type :viewer-bundle
    ;;               :profile-id (:id prof2)
    ;;               :token @token
    ;;               :file-id (:id file)
    ;;               :page-id (get-in file [:data :pages 0])}
    ;;         out  (th/try-on! (sq/handle data))]

    ;;     ;; (th/print-result! out)

    ;;     (let [result (:result out)]
    ;;       (t/is (contains? result :page))
    ;;       (t/is (contains? result :file))
    ;;       (t/is (contains? result :project)))))

    ;; (t/testing "authenticated with token"
    ;;   (let [data {::sq/type :viewer-bundle
    ;;               :token @token
    ;;               :file-id (:id file)
    ;;               :page-id (get-in file [:data :pages 0])}
    ;;         out  (th/try-on! (sq/handle data))]

    ;;     ;; (th/print-result! out)

    ;;     (let [result (:result out)]
    ;;       (t/is (contains? result :page))
    ;;       (t/is (contains? result :file))
    ;;       (t/is (contains? result :project)))))
    ))
