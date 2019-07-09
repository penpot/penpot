(ns uxbox.tests.test-images
  (:require [clojure.test :as t]
            [promesa.core :as p]
            [suricatta.core :as sc]
            [clojure.java.io :as io]
            [datoteka.storages :as st]
            [uxbox.db :as db]
            [uxbox.sql :as sql]
            [uxbox.media :as media]
            [uxbox.http :as http]
            [uxbox.services.images :as images]
            [uxbox.services :as usv]
            [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-http-list-image-collections
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "coll1"}
          coll (images/create-collection conn data)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/image-collections")
              [status data] (th/http-get user uri)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= 1 (count data))))))))

(t/deftest test-http-create-image-collection
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/image-collections")
              data {:user (:id user)
                    :name "coll1"}
              params {:body data}
              [status data] (th/http-post user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 201 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "coll1")))))))

(t/deftest test-http-update-image-collection
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "coll1"}
          coll (images/create-collection conn data)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/image-collections/" (:id coll))
              params {:body (assoc coll :name "coll2")}
              [status data] (th/http-put user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "coll2")))))))

(t/deftest test-http-image-collection-delete
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "coll1"
                :data #{1}}
          coll (images/create-collection conn data)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/image-collections/" (:id coll))
              [status data] (th/http-delete user uri)]
          (t/is (= 204 status))
          (let [sqlv (sql/get-image-collections {:user (:id user)})
                result (sc/fetch conn sqlv)]
            (t/is (empty? result))))))))

(t/deftest test-http-create-image
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/images")
              parts [{:name "sample.jpg"
                      :part-name "file"
                      :content (io/input-stream
                                (io/resource "uxbox/tests/_files/sample.jpg"))}
                     {:part-name "user" :content (str (:id user))}
                     {:part-name "width" :content "100"}
                     {:part-name "height" :content "100"}
                     {:part-name "mimetype" :content "image/png"}]
              [status data] (th/http-multipart user uri parts)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 201 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "sample.jpg")))))))

(t/deftest test-http-update-image
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "test.png"
                :path "some/path"
                :width 100
                :height 100
                :mimetype "image/png"
                :collection nil}
          img (images/create-image conn data)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/images/" (:id img))
              params {:body (assoc img :name "my stuff")}
              [status data] (th/http-put user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "my stuff")))))))

(t/deftest test-http-copy-image
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          storage media/images-storage
          filename "sample.jpg"
          rcs (io/resource "uxbox/tests/_files/sample.jpg")
          path @(st/save storage filename rcs)
          data {:user (:id user)
                :name filename
                :path (str path)
                :width 100
                :height 100
                :mimetype "image/jpg"
                :collection nil}
          img (images/create-image conn data)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/images/" (:id img) "/copy")
              body {:id (:id img)
                    :collection nil}
              params {:body body}
              [status data] (th/http-put user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (let [sqlv (sql/get-images {:user (:id user) :collection nil})
                result (sc/fetch conn sqlv)]
            (t/is (= 2 (count result)))))))))

(t/deftest test-http-delete-image
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "test.png"
                :path "some/path"
                :width 100
                :height 100
                :mimetype "image/png"
                :collection nil}
          img (images/create-image conn data)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/images/" (:id img))
              [status data] (th/http-delete user uri)]
          (t/is (= 204 status))
          (let [sqlv (sql/get-images {:user (:id user) :collection nil})
                result (sc/fetch conn sqlv)]
            (t/is (empty? result))))))))

(t/deftest test-http-list-images
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "test.png"
                :path "some/path"
                :width 100
                :height 100
                :mimetype "image/png"
                :collection nil}
          img (images/create-image conn data)]
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/library/images")
              [status data] (th/http-get user uri)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= 1 (count data))))))))
