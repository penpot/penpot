;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.tests.test-services-viewer
  (:require
   [clojure.test :as t]
   [datoteka.core :as fs]
   [uxbox.common.uuid :as uuid]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.tests.helpers :as th]
   [uxbox.util.storage :as ust]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest retrieve-bundle
  (let [prof    (th/create-profile db/pool 1)
        prof2   (th/create-profile db/pool 2)
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)

        file  (th/create-file db/pool (:id prof) proj-id false 1)
        page  (th/create-page db/pool (:id prof) (:id file) 1)
        token (atom nil)]


    (t/testing "authenticated with page-id"
      (let [data {::sq/type :viewer-bundle
                  :profile-id (:id prof)
                  :page-id (:id page)}

            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (contains? result :page))
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    (t/testing "generate share token"
      (let [data {::sm/type :generate-page-share-token
                  :id (:id page)}
             out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (string? (:share-token result)))
          (reset! token (:share-token result)))))

    (t/testing "authenticated with page-id"
      (let [data {::sq/type :viewer-bundle
                  :profile-id (:id prof2)
                  :page-id (:id page)}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)

        (let [error (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :service-error))
          (t/is (= (:name error-data) :uxbox.services.queries.viewer/viewer-bundle)))

        (let [error (ex-cause (:error out))
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found)))))

    (t/testing "authenticated with page-id and token"
      (let [data {::sq/type :viewer-bundle
                  :profile-id (:id prof2)
                  :page-id (:id page)
                  :share-token @token}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)

        (let [result (:result out)]
          (t/is (contains? result :page))
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    (t/testing "not authenticated with page-id and token"
      (let [data {::sq/type :viewer-bundle
                  :page-id (:id page)
                  :share-token @token}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)

        (let [result (:result out)]
          (t/is (contains? result :page))
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))
    ))
