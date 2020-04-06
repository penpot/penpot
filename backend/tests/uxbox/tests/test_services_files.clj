(ns uxbox.tests.test-services-files
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
   [uxbox.util.uuid :as uuid]
   [vertx.util :as vu]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest files-crud
  (let [prof @(th/create-profile db/pool 1)
        team (:default-team prof)
        proj (:default-project prof)
        file-id (uuid/next)
        page-id (uuid/next)]

    (t/testing "create file"
      (let [data {::sm/type :create-file
                  :profile-id (:id prof)
                  :project-id (:id proj)
                  :id file-id
                  :name "test file"}
            out (th/try-on! (sm/handle data))]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:name data) (:name result)))
          (t/is (= (:id proj) (:project-id result))))))

    (t/testing "rename file"
      (let [data {::sm/type :rename-file
                  :id file-id
                  :name "new name"
                  :profile-id (:id prof)}
            out  (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "query files"
      (let [data {::sq/type :files
                  :project-id (:id proj)
                  :profile-id (:id prof)}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= file-id (get-in result [0 :id])))
          (t/is (= "new name" (get-in result [0 :name])))
          (t/is (= 1 (count (get-in result [0 :pages])))))))

    (t/testing "query single file with users"
      (let [data {::sq/type :file-with-users
                  :profile-id (:id prof)
                  :id file-id}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= file-id (:id result)))
          (t/is (= "new name" (:name result)))
          (t/is (vector? (:pages result)))
          (t/is (= 1 (count (:pages result))))
          (t/is (vector? (:users result)))
          (t/is (= (:id prof) (get-in result [:users 0 :id]))))))

    (t/testing "query single file without users"
      (let [data {::sq/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= file-id (:id result)))
          (t/is (= "new name" (:name result)))
          (t/is (vector? (:pages result)))
          (t/is (= 1 (count (:pages result))))
          (t/is (nil? (:users result))))))

    (t/testing "delete file"
      (let [data {::sm/type :delete-file
                  :id file-id
                  :profile-id (:id prof)}
            out (th/try-on! (sm/handle data))]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query single file after delete"
      (let [data {::sq/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)

        (let [error (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :service-error))
          (t/is (= (:name error-data) :uxbox.services.queries.files/file)))

        (let [error (ex-cause (:error out))
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found)))))

    (t/testing "query list files after delete"
      (let [data {::sq/type :files
                  :project-id (:id proj)
                  :profile-id (:id prof)}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))

(t/deftest file-images-crud
  (let [prof @(th/create-profile db/pool 1)
        team (:default-team prof)
        proj (:default-project prof)
        file @(th/create-file db/pool (:id prof) (:id proj) 1)]

    (t/testing "upload file image"
      (let [content {:name "sample.jpg"
                     :path "tests/uxbox/tests/_files/sample.jpg"
                     :mtype "image/jpeg"
                     :size 312043}
            data {::sm/type :upload-file-image
                  :profile-id (:id prof)
                  :file-id (:id file)
                  :name "testfile"
                  :content content
                  :width 800
                  :height 800}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:id file) (:file-id result)))
          (t/is (= (:name data) (:name result)))
          (t/is (= (:width data) (:width result)))
          (t/is (= (:height data) (:height result)))
          (t/is (= (:mtype content) (:mtype result)))

          (t/is (string? (:path result)))
          (t/is (string? (:uri result)))
          (t/is (string? (:thumb-path result)))
          (t/is (string? (:thumb-uri result))))))

    (t/testing "import from library"
      (let [lib @(th/create-image-library db/pool (:id team) 1)
            image-id (uuid/next)

            content {:name "sample.jpg"
                     :path "tests/uxbox/tests/_files/sample.jpg"
                     :mtype "image/jpeg"
                     :size 312043}

            data {::sm/type :upload-image
                  :id image-id
                  :profile-id (:id prof)
                  :library-id (:id lib)
                  :name "testfile"
                  :content content}
            out1 (th/try-on! (sm/handle data))]

        ;; (th/print-result! out1)
        (t/is (nil? (:error out1)))

        (let [result (:result out1)]
          (t/is (= image-id (:id result)))
          (t/is (= "testfile" (:name result)))
          (t/is (= "image/jpeg" (:mtype result)))
          (t/is (= "image/webp" (:thumb-mtype result))))

        (let [data2 {::sm/type :import-image-to-file
                     :image-id image-id
                     :file-id (:id file)
                     :profile-id (:id prof)}
              out2 (th/try-on! (sm/handle data2))]

          ;; (th/print-result! out2)
          (t/is (nil? (:error out2)))

          (let [result1 (:result out1)
                result2 (:result out2)]
            (t/is (not= (:path result2)
                        (:path result1)))
            (t/is (not= (:thumb-path result2)
                        (:thumb-path result1)))))))
    ))


;; TODO: delete file image
