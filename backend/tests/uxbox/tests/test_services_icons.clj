(ns uxbox.tests.test-services-icons
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

(t/deftest icon-collections-crud
  (let [id      (uuid/next)
        profile @(th/create-profile db/pool 2)]

    (t/testing "create collection"
      (let [data {::sm/type :create-icon-collection
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
      (let [data {::sm/type :rename-icon-collection
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
      (let [data {::sq/type :icon-collections
                  :profile-id (:id profile)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= 1 (count (:result out))))
        (t/is (= (:id profile) (get-in out [:result 0 :profile-id])))
        (t/is (= id (get-in out [:result 0 :id])))))

    (t/testing "delete collection"
      (let [data {::sm/type :delete-icon-collection
                  :profile-id (:id profile)
                  :id id}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query collections after delete"
      (let [data {::sq/type :icon-collections
                  :profile-id (:id profile)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= 0 (count (:result out))))))
    ))

(t/deftest icons-crud
  (let [profile @(th/create-profile db/pool 1)
        coll @(th/create-icon-collection db/pool (:id profile) 1)
        icon-id (uuid/next)]

    (t/testing "upload icon to collection"
      (let [data {::sm/type :create-icon
                  :id icon-id
                  :profile-id (:id profile)
                  :collection-id (:id coll)
                  :name "testfile"
                  :content "<rect></rect>"
                  :metadata {:width 100
                             :height 100
                             :view-box [0 0 100 100]
                             :mimetype "text/svg"}}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result)))
          (t/is (= (:content data) (:content result))))))

    (t/testing "list icons by collection"
      (let [data {::sq/type :icons
                  :profile-id (:id profile)
                  :collection-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= icon-id (get-in out [:result 0 :id])))
        (t/is (= "testfile" (get-in out [:result 0 :name])))))

    (t/testing "single icon"
      (let [data {::sq/type :icon
                  :profile-id (:id profile)
                  :id icon-id}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= icon-id (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))))

    (t/testing "delete icons"
      (let [data {::sm/type :delete-icon
                  :profile-id (:id profile)
                  :id icon-id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (get-in out [:result])))))

    (t/testing "query icon after delete"
      (let [data {::sq/type :icon
                  :profile-id (:id profile)
                  :id icon-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :service-error)))

        (let [error (ex-cause (:error out))]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :not-found)))))

    (t/testing "query icons after delete"
      (let [data {::sq/type :icons
                  :profile-id (:id profile)
                  :collection-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))
