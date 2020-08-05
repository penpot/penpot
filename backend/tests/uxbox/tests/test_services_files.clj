;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.tests.test-services-files
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

(t/deftest files-crud
  (let [prof (th/create-profile db/pool 1)
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        file-id (uuid/next)
        page-id (uuid/next)]

    (t/testing "create file"
      (let [data {::sm/type :create-file
                  :profile-id (:id prof)
                  :project-id proj-id
                  :id file-id
                  :is-shared false
                  :name "test file"}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:name data) (:name result)))
          (t/is (= proj-id (:project-id result))))))

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
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= file-id (get-in result [0 :id])))
          (t/is (= "new name" (get-in result [0 :name])))
          (t/is (= 1 (count (get-in result [0 :pages])))))))

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
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))

;; (t/deftest file-images-crud
;;   (let [prof    (th/create-profile db/pool 1)
;;         team-id (:default-team-id prof)
;;         proj-id (:default-project-id prof)
;;         file    (th/create-file db/pool (:id prof) proj-id 1)]
;;
;;     (t/testing "create file image from url"
;;       (let [url "https://raw.githubusercontent.com/uxbox/uxbox/develop/frontend/resources/images/penpot-login.jpg"
;;             data {::sm/type :add-file-image-from-url
;;                   :profile-id (:id prof)
;;                   :file-id (:id file)
;;                   :url url}
;;             out (th/try-on! (sm/handle data))]
;;
;;         ;; (th/print-result! out)
;;         (t/is (nil? (:error out)))
;;
;;         (let [result (:result out)]
;;           (t/is (= (:id file) (:file-id result)))
;;           (t/is (not (nil? (:name result))))
;;           (t/is (= 787 (:width result)))
;;           (t/is (= 2000 (:height result)))
;;           (t/is (= "image/jpeg" (:mtype result)))
;;
;;           (t/is (string? (:path result)))
;;           (t/is (string? (:uri result)))
;;           (t/is (string? (:thumb-path result)))
;;           (t/is (string? (:thumb-uri result))))))
;;
;;     (t/testing "upload file image"
;;       (let [content {:filename "sample.jpg"
;;                      :tempfile (th/tempfile "uxbox/tests/_files/sample.jpg")
;;                      :content-type "image/jpeg"
;;                      :size 312043}
;;             data {::sm/type :upload-file-image
;;                   :profile-id (:id prof)
;;                   :file-id (:id file)
;;                   :name "testfile"
;;                   :content content}
;;
;;             out (th/try-on! (sm/handle data))]
;;
;;         ;; (th/print-result! out)
;;         (t/is (nil? (:error out)))
;;
;;         (let [result (:result out)]
;;           (t/is (= (:id file) (:file-id result)))
;;           (t/is (= (:name data) (:name result)))
;;           (t/is (= 800 (:width result)))
;;           (t/is (= 800 (:height result)))
;;           (t/is (= (:content-type content) (:mtype result)))
;;
;;           (t/is (string? (:path result)))
;;           (t/is (string? (:uri result)))
;;           (t/is (string? (:thumb-path result)))
;;           (t/is (string? (:thumb-uri result))))))
;;
;;     ;; (t/testing "import from library"
;;     ;;   (let [lib      (th/create-image-library db/pool team-id 1)
;;     ;;         image-id (uuid/next)
;;     ;;
;;     ;;         content {:filename "sample.jpg"
;;     ;;                  :tempfile (th/tempfile "uxbox/tests/_files/sample.jpg")
;;     ;;                  :content-type "image/jpeg"
;;     ;;                  :size 312043}
;;     ;;
;;     ;;         data {::sm/type :upload-image
;;     ;;               :id image-id
;;     ;;               :profile-id (:id prof)
;;     ;;               :library-id (:id lib)
;;     ;;               :name "testfile"
;;     ;;               :content content}
;;     ;;         out1 (th/try-on! (sm/handle data))]
;;     ;;
;;     ;;     ;; (th/print-result! out1)
;;     ;;     (t/is (nil? (:error out1)))
;;     ;;
;;     ;;     (let [result (:result out1)]
;;     ;;       (t/is (= image-id (:id result)))
;;     ;;       (t/is (= "testfile" (:name result)))
;;     ;;       (t/is (= "image/jpeg" (:mtype result)))
;;     ;;       (t/is (= "image/jpeg" (:thumb-mtype result))))
;;     ;;
;;     ;;     (let [data2 {::sm/type :import-image-to-file
;;     ;;                  :image-id image-id
;;     ;;                  :file-id (:id file)
;;     ;;                  :profile-id (:id prof)}
;;     ;;           out2 (th/try-on! (sm/handle data2))]
;;     ;;
;;     ;;       ;; (th/print-result! out2)
;;     ;;       (t/is (nil? (:error out2)))
;;     ;;
;;     ;;       (let [result1 (:result out1)
;;     ;;             result2 (:result out2)]
;;     ;;         (t/is (not= (:path result2)
;;     ;;                     (:path result1)))
;;     ;;         (t/is (not= (:thumb-path result2)
;;     ;;                     (:thumb-path result1)))))))
;;     ))


;; TODO: delete file image
