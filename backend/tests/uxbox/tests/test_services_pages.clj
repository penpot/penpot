(ns uxbox.tests.test-services-pages
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.common.pages :as cp]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.common.uuid :as uuid]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest pages-crud
  (let [prof (th/create-profile db/pool 1)
        team-id (:default-team prof)
        proj-id (:default-project prof)
        file (th/create-file db/pool (:id prof) proj-id 1)
        page-id (uuid/next)]

    (t/testing "create page"
      (let [data {::sm/type :create-page
                  :data cp/default-page-data
                  :file-id (:id file)
                  :id page-id
                  :ordering 1
                  :name "test page"
                  :profile-id (:id prof)}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (uuid? (:id result)))
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result)))
          (t/is (= (:data data) (:data result)))
          (t/is (nil? (:share-token result)))
          (t/is (= 0 (:version result)))
          (t/is (= 0 (:revn result))))))

    (t/testing "generate share token"
      (let [data {::sm/type :generate-page-share-token
                  :id page-id}
             out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (string? (:share-token result))))))

    (t/testing "query pages"
      (let [data {::sq/type :pages
                  :file-id (:id file)
                  :profile-id (:id prof)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (vector? result))
          (t/is (= 1 (count result)))
          (t/is (= page-id (get-in result [0 :id])))
          (t/is (= "test page" (get-in result [0 :name])))
          (t/is (string? (get-in result [0 :share-token])))
          (t/is (:id file) (get-in result [0 :file-id])))))

    (t/testing "delete page"
      (let [data {::sm/type :delete-page
                  :id page-id
                  :profile-id (:id prof)}
            out (th/try-on! (sm/handle data))]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query pages after delete"
      (let [data {::sq/type :pages
                  :file-id (:id file)
                  :profile-id (:id prof)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (vector? result))
          (t/is (= 0 (count result))))))
    ))

(t/deftest update-page-data
  (let [prof    (th/create-profile db/pool 1)
        team-id (:default-team prof)
        proj-id (:default-project prof)
        file    (th/create-file db/pool (:id prof) proj-id 1)
        page-id (uuid/next)]

    (t/testing "create empty page"
      (let [data {::sm/type :create-page
                  :data cp/default-page-data
                  :file-id (:id file)
                  :id page-id
                  :ordering 1
                  :name "test page"
                  :profile-id (:id prof)}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (uuid? (:id result)))
          (t/is (= (:id data) (:id result))))))


    (t/testing "successfully update data"
      (let [sid  (uuid/next)
            data {::sm/type :update-page
                  :id page-id
                  :revn 0
                  :session-id uuid/zero
                  :profile-id (:id prof)
                  :changes [{:type :add-obj
                             :frame-id uuid/zero
                             :id sid
                             :obj {:id sid
                                   :name "Rect"
                                   :frame-id uuid/zero
                                   :type :rect}}]}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (:revn result)))
          (t/is (= (:id data) (:page-id result)))
          (t/is (vector (:changes result)))
          (t/is (= 1 (count (:changes result))))
          (t/is (= :add-obj (get-in result [:changes 0 :type]))))))

    (t/testing "conflict error"
      (let [data {::sm/type :update-page
                  :session-id uuid/zero
                  :id page-id
                  :revn 99
                  :profile-id (:id prof)
                  :changes []}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (let [error (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :service-error))
          (t/is (= (:name error-data) :uxbox.services.mutations.pages/update-page)))

        (let [error (ex-cause (:error out))
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :validation))
          (t/is (= (:code error-data) :revn-conflict)))))
    ))


(t/deftest update-page-data-2
  (let [prof    (th/create-profile db/pool 1)
        team-id (:default-team prof)
        proj-id (:default-project prof)
        file    (th/create-file db/pool (:id prof) proj-id 1)
        page    (th/create-page db/pool (:id prof) (:id file) 1)]
    (t/testing "lagging changes"
      (let [sid  (uuid/next)
            data {::sm/type :update-page
                  :id (:id page)
                  :revn 0
                  :session-id uuid/zero
                  :profile-id (:id prof)
                  :changes [{:type :add-obj
                             :id sid
                             :frame-id uuid/zero
                             :obj {:id sid
                                   :name "Rect"
                                   :frame-id uuid/zero
                                   :type :rect}}]}
            out1 (th/try-on! (sm/handle data))
            out2 (th/try-on! (sm/handle data))
            ]

        ;; (th/print-result! out1)
        ;; (th/print-result! out2)

        (t/is (nil? (:error out1)))
        (t/is (nil? (:error out2)))

        (t/is (= 1 (count (get-in out1 [:result :changes]))))
        (t/is (= 2 (count (get-in out2 [:result :changes]))))

        (t/is (= (:id data) (get-in out1 [:result :page-id])))
        (t/is (= (:id data) (get-in out2 [:result :page-id])))
    ))))


