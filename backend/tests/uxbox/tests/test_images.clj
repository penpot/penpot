(ns uxbox.tests.test-images
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

(t/deftest images-collections-crud
  (let [id    (uuid/next)
        user @(th/create-user db/pool 2)]

    (t/testing "create collection"
      (let [data {::sm/type :create-images-collection
                  :name "sample collection"
                  :user (:id user)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= (:id user) (get-in out [:result :user-id])))
        (t/is (= (:name data) (get-in out [:result :name])))))

    (t/testing "update collection"
      (let [data {::sm/type :rename-images-collection
                  :name "sample collection renamed"
                  :user (:id user)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= id (get-in out [:result :id])))
        (t/is (= (:id user) (get-in out [:result :user-id])))
        (t/is (= (:name data) (get-in out [:result :name])))))

    (t/testing "query collections"
      (let [data {::sq/type :images-collections
                  :user (:id user)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= 1 (count (:result out))))
        (t/is (= (:id user) (get-in out [:result 0 :user-id])))
        (t/is (= id (get-in out [:result 0 :id])))))

    (t/testing "delete collection"
      (let [data {::sm/type :delete-images-collection
                  :user (:id user)
                  :id id}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= id (get-in out [:result :id])))))

    (t/testing "query collections after delete"
      (let [data {::sq/type :images-collections
                  :user (:id user)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= 0 (count (:result out))))))
    ))

(t/deftest images-crud
  (let [user @(th/create-user db/pool 1)
        coll @(th/create-images-collection db/pool (:id user) 1)
        image-id (uuid/next)]

    (t/testing "upload image to collection"
      (let [content {:name "sample.jpg"
                     :path "tests/uxbox/tests/_files/sample.jpg"
                     :mtype "image/jpeg"
                     :size 312043}
            data {::sm/type :upload-image
                  :id image-id
                  :user (:id user)
                  :collection-id (:id coll)
                  :name "testfile"
                  :content content}
            out (th/try-on! (sm/handle data))]
        ;; out  (with-redefs [vc/*context* (vc/get-or-create-context system)]
        ;;        (th/try-on! (sm/handle data)))]

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
      (let [data {::sq/type :images-by-collection
                  :user (:id user)
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

    (t/testing "get image by id"
      (let [data {::sq/type :image-by-id
                  :user (:id user)
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
    ))

;; TODO: (soft) delete image

