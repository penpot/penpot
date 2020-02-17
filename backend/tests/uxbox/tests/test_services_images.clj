(ns uxbox.tests.test-services-images
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

(t/deftest image-collections-crud
  (let [id      (uuid/next)
        profile @(th/create-profile db/pool 2)]

    (t/testing "create collection"
      (let [data {::sm/type :create-image-collection
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
      (let [data {::sm/type :rename-image-collection
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
      (let [data {::sq/type :image-collections
                  :profile-id (:id profile)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= 1 (count (:result out))))
        (t/is (= (:id profile) (get-in out [:result 0 :profile-id])))
        (t/is (= id (get-in out [:result 0 :id])))))

    (t/testing "delete collection"
      (let [data {::sm/type :delete-image-collection
                  :profile-id (:id profile)
                  :id id}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query collections after delete"
      (let [data {::sq/type :image-collections
                  :profile-id (:id profile)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= 0 (count (:result out))))))
    ))

(t/deftest images-crud
  (let [profile @(th/create-profile db/pool 1)
        coll @(th/create-image-collection db/pool (:id profile) 1)
        image-id (uuid/next)]

    (t/testing "upload image to collection"
      (let [content {:name "sample.jpg"
                     :path "tests/uxbox/tests/_files/sample.jpg"
                     :mtype "image/jpeg"
                     :size 312043}
            data {::sm/type :upload-image
                  :id image-id
                  :profile-id (:id profile)
                  :collection-id (:id coll)
                  :name "testfile"
                  :content content}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= image-id (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))
        (t/is (= "image/jpeg" (get-in out [:result :mtype])))
        (t/is (= "image/webp" (get-in out [:result :thumb-mtype])))
        (t/is (= 800 (get-in out [:result :width])))
        (t/is (= 800 (get-in out [:result :height])))

        (t/is (string? (get-in out [:result :path])))
        (t/is (string? (get-in out [:result :thumb-path])))
        (t/is (string? (get-in out [:result :uri])))
        (t/is (string? (get-in out [:result :thumb-uri])))))

    (t/testing "list images by collection"
      (let [data {::sq/type :images
                  :profile-id (:id profile)
                  :collection-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= image-id (get-in out [:result 0 :id])))
        (t/is (= "testfile" (get-in out [:result 0 :name])))
        (t/is (= "image/jpeg" (get-in out [:result 0 :mtype])))
        (t/is (= "image/webp" (get-in out [:result 0 :thumb-mtype])))
        (t/is (= 800 (get-in out [:result 0 :width])))
        (t/is (= 800 (get-in out [:result 0 :height])))

        (t/is (string? (get-in out [:result 0 :path])))
        (t/is (string? (get-in out [:result 0 :thumb-path])))
        (t/is (string? (get-in out [:result 0 :uri])))
        (t/is (string? (get-in out [:result 0 :thumb-uri])))))

    (t/testing "single image"
      (let [data {::sq/type :image
                  :profile-id (:id profile)
                  :id image-id}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= image-id (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))
        (t/is (= "image/jpeg" (get-in out [:result :mtype])))
        (t/is (= "image/webp" (get-in out [:result :thumb-mtype])))
        (t/is (= 800 (get-in out [:result :width])))
        (t/is (= 800 (get-in out [:result :height])))

        (t/is (string? (get-in out [:result :path])))
        (t/is (string? (get-in out [:result :thumb-path])))
        (t/is (string? (get-in out [:result :uri])))
        (t/is (string? (get-in out [:result :thumb-uri])))))

    (t/testing "delete images"
      (let [data {::sm/type :delete-image
                  :profile-id (:id profile)
                  :id image-id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (get-in out [:result])))))

    (t/testing "query image after delete"
      (let [data {::sq/type :image
                  :profile-id (:id profile)
                  :id image-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :service-error)))

        (let [error (ex-cause (:error out))]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :not-found)))))

    (t/testing "query images after delete"
      (let [data {::sq/type :images
                  :profile-id (:id profile)
                  :collection-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))
