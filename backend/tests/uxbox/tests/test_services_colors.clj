(ns uxbox.tests.test-services-colors
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [datoteka.core :as fs]
   [clojure.java.io :as io]
   [uxbox.db :as db]
   [uxbox.core :refer [system]]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.storage :as ust]
   [uxbox.util.uuid :as uuid]
   [uxbox.tests.helpers :as th]
   [vertx.core :as vc]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest color-collections-crud
  (let [id      (uuid/next)
        profile @(th/create-profile db/pool 2)]

    (t/testing "create collection"
      (let [data {::sm/type :create-color-collection
                  :name "sample collection"
                  :profile-id (:id profile)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:id profile)  (:profile-id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "update collection"
      (let [data {::sm/type :rename-color-collection
                  :name "sample collection renamed"
                  :profile-id (:id profile)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= id (get-in out [:result :id])))
        (t/is (= (:id profile) (get-in out [:result :profile-id])))
        (t/is (= (:name data) (get-in out [:result :name])))))

    (t/testing "query collections"
      (let [data {::sq/type :color-collections
                  :profile-id (:id profile)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= 1 (count (:result out))))
        (t/is (= (:id profile) (get-in out [:result 0 :profile-id])))
        (t/is (= id (get-in out [:result 0 :id])))))

    (t/testing "delete collection"
      (let [data {::sm/type :delete-color-collection
                  :profile-id (:id profile)
                  :id id}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query collections after delete"
      (let [data {::sq/type :color-collections
                  :profile-id (:id profile)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= 0 (count (:result out))))))
    ))

(t/deftest colors-crud
  (let [profile @(th/create-profile db/pool 1)
        coll @(th/create-color-collection db/pool (:id profile) 1)
        color-id (uuid/next)]

    (t/testing "upload color to collection"
      (let [data {::sm/type :create-color
                  :id color-id
                  :profile-id (:id profile)
                  :collection-id (:id coll)
                  :name "testfile"
                  :content "#222222"}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result)))
          (t/is (= (:content data) (:content result))))))

    (t/testing "list colors by collection"
      (let [data {::sq/type :colors
                  :profile-id (:id profile)
                  :collection-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= color-id (get-in out [:result 0 :id])))
        (t/is (= "testfile" (get-in out [:result 0 :name])))))

    (t/testing "single color"
      (let [data {::sq/type :color
                  :profile-id (:id profile)
                  :id color-id}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= color-id (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))))

    (t/testing "delete colors"
      (let [data {::sm/type :delete-color
                  :profile-id (:id profile)
                  :id color-id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (get-in out [:result])))))

    (t/testing "query color after delete"
      (let [data {::sq/type :color
                  :profile-id (:id profile)
                  :id color-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :service-error)))

        (let [error (ex-cause (:error out))]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :not-found)))))

    (t/testing "query colors after delete"
      (let [data {::sq/type :colors
                  :profile-id (:id profile)
                  :collection-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))
