(ns uxbox.tests.test-services-viewer
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [datoteka.core :as fs]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.core :refer [system]]
   [uxbox.http :as http]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.tests.helpers :as th]
   [uxbox.util.storage :as ust]
   [uxbox.common.uuid :as uuid]
   [vertx.util :as vu]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest retrieve-bundle
  (let [prof @(th/create-profile db/pool 1)
        prof2 @(th/create-profile db/pool 2)
        team (:default-team prof)
        proj (:default-project prof)

        file @(th/create-file db/pool (:id prof) (:id proj) 1)
        page @(th/create-page db/pool (:id prof) (:id file) 1)
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
