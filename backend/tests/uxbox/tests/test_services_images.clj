;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.tests.test-services-images
  (:require
   [clojure.test :as t]
   [datoteka.core :as fs]
   [uxbox.common.uuid :as uuid]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.tests.helpers :as th]
   [uxbox.util.storage :as ust]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest image-libraries-crud
  (let [id      (uuid/next)
        prof    (th/create-profile db/pool 2)
        team-id (:default-team-id prof)]

    (t/testing "create library"
      (let [data {::sm/type :create-image-library
                  :name "sample library"
                  :profile-id (:id prof)
                  :team-id team-id
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= team-id  (:team-id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "rename library"
      (let [data {::sm/type :rename-image-library
                  :name "renamed"
                  :profile-id (:id prof)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= id (:id result)))
          (t/is (= "renamed" (:name result))))))

    (t/testing "query single library"
      (let [data {::sq/type :image-library
                  :profile-id (:id prof)
                  :id id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= id (:id result)))
          (t/is (= "renamed" (:name result))))))

    (t/testing "query libraries"
      (let [data {::sq/type :image-libraries
                  :team-id team-id
                  :profile-id (:id prof)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= id (get-in result [0 :id]))))))

    (t/testing "delete library"
      (let [data {::sm/type :delete-image-library
                  :profile-id (:id prof)
                  :id id}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query libraries after delete"
      (let [data {::sq/type :image-libraries
                  :profile-id (:id prof)
                  :team-id team-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= 0 (count (:result out))))))
    ))

(t/deftest images-crud
  (let [prof       (th/create-profile db/pool 1)
        team-id    (:default-team-id prof)
        image-id-1 (uuid/next)
        image-id-2 (uuid/next)
        lib        (th/create-image-library db/pool team-id 1)]

    (t/testing "create image from url to library"
      (let [url "https://raw.githubusercontent.com/uxbox/uxbox/develop/frontend/resources/images/penpot-login.jpg"
            data {::sm/type :add-image-from-url
                  :id image-id-1
                  :profile-id (:id prof)
                  :library-id (:id lib)
                  :url url}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= image-id-1 (get-in out [:result :id])))
        (t/is (not (nil? (get-in out [:result :name]))))
        (t/is (= "image/jpeg" (get-in out [:result :mtype])))
        (t/is (= "image/jpeg" (get-in out [:result :thumb-mtype])))
        (t/is (= 787 (get-in out [:result :width])))
        (t/is (= 2000 (get-in out [:result :height])))

        (t/is (string? (get-in out [:result :path])))
        (t/is (string? (get-in out [:result :thumb-path])))
        (t/is (string? (get-in out [:result :uri])))
        (t/is (string? (get-in out [:result :thumb-uri])))))

    (t/testing "upload image to library"
      (let [content {:filename "sample.jpg"
                     :tempfile (th/tempfile "uxbox/tests/_files/sample.jpg")
                     :content-type "image/jpeg"
                     :size 312043}
            data {::sm/type :upload-image
                  :id image-id-2
                  :profile-id (:id prof)
                  :library-id (:id lib)
                  :name "testfile"
                  :content content}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= image-id-2 (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))
        (t/is (= "image/jpeg" (get-in out [:result :mtype])))
        (t/is (= "image/jpeg" (get-in out [:result :thumb-mtype])))
        (t/is (= 800 (get-in out [:result :width])))
        (t/is (= 800 (get-in out [:result :height])))

        (t/is (string? (get-in out [:result :path])))
        (t/is (string? (get-in out [:result :thumb-path])))
        (t/is (string? (get-in out [:result :uri])))
        (t/is (string? (get-in out [:result :thumb-uri])))))

    (t/testing "list images by library"
      (let [data {::sq/type :images
                  :profile-id (:id prof)
                  :library-id (:id lib)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        ;; Result is ordered by creation date descendent
        (t/is (= image-id-2 (get-in out [:result 0 :id])))
        (t/is (= "testfile" (get-in out [:result 0 :name])))
        (t/is (= "image/jpeg" (get-in out [:result 0 :mtype])))
        (t/is (= "image/jpeg" (get-in out [:result 0 :thumb-mtype])))
        (t/is (= 800 (get-in out [:result 0 :width])))
        (t/is (= 800 (get-in out [:result 0 :height])))

        (t/is (string? (get-in out [:result 0 :path])))
        (t/is (string? (get-in out [:result 0 :thumb-path])))
        (t/is (string? (get-in out [:result 0 :uri])))
        (t/is (string? (get-in out [:result 0 :thumb-uri])))))

    (t/testing "single image"
      (let [data {::sq/type :image
                  :profile-id (:id prof)
                  :id image-id-2}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= image-id-2 (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))
        (t/is (= "image/jpeg" (get-in out [:result :mtype])))
        (t/is (= "image/jpeg" (get-in out [:result :thumb-mtype])))
        (t/is (= 800 (get-in out [:result :width])))
        (t/is (= 800 (get-in out [:result :height])))

        (t/is (string? (get-in out [:result :path])))
        (t/is (string? (get-in out [:result :thumb-path])))
        (t/is (string? (get-in out [:result :uri])))
        (t/is (string? (get-in out [:result :thumb-uri])))))

    (t/testing "delete images"
      (let [data {::sm/type :delete-image
                  :profile-id (:id prof)
                  :id image-id-1}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query image after delete"
      (let [data {::sq/type :image
                  :profile-id (:id prof)
                  :id image-id-1}
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
                  :profile-id (:id prof)
                  :library-id (:id lib)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= 1 (count result))))))
    ))
