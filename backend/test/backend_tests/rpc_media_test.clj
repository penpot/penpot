;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-media-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.client :as http]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest media-object-from-url
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        url    "https://raw.githubusercontent.com/uxbox/uxbox/develop/sample_media/images/unsplash/anna-pelzer.jpg"
        params {::th/type :create-file-media-object-from-url
                ::rpc/profile-id (:id prof)
                :file-id    (:id file)
                :is-local   true
                :url        url}
        out   (th/command! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [{:keys [media-id thumbnail-id] :as result} (:result out)]
      (t/is (= (:id file) (:file-id result)))
      (t/is (= 1024 (:width result)))
      (t/is (= 683  (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? media-id))
      (t/is (uuid? thumbnail-id))
      (let [storage (:app.storage/storage th/*system*)
            mobj1   (sto/get-object storage media-id)
            mobj2   (sto/get-object storage thumbnail-id)]
        (t/is (sto/object? mobj1))
        (t/is (sto/object? mobj2))
        (t/is (= 122785 (:size mobj1)))
        (t/is (= 3297 (:size mobj2)))))))

(t/deftest media-object-upload
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        mfile  {:filename "sample.jpg"
                :path (th/tempfile "backend_tests/test_files/sample.jpg")
                :mtype "image/jpeg"
                :size 312043}

        params {::th/type :upload-file-media-object
                ::rpc/profile-id (:id prof)
                :file-id (:id file)
                :is-local true
                :name "testfile"
                :content mfile}
        out    (th/command! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [{:keys [media-id thumbnail-id] :as result} (:result out)]
      (t/is (= (:id file) (:file-id result)))
      (t/is (= 800 (:width result)))
      (t/is (= 800  (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? media-id))
      (t/is (uuid? thumbnail-id))
      (let [storage (:app.storage/storage th/*system*)
            mobj1   (sto/get-object storage media-id)
            mobj2   (sto/get-object storage thumbnail-id)]
        (t/is (sto/object? mobj1))
        (t/is (sto/object? mobj2))
        (t/is (= 312043 (:size mobj1)))
        (t/is (= 3890   (:size mobj2)))))))


(t/deftest media-object-upload-idempotency
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        mfile  {:filename "sample.jpg"
                :path (th/tempfile "backend_tests/test_files/sample.jpg")
                :mtype "image/jpeg"
                :size 312043}

        params {::th/type :upload-file-media-object
                ::rpc/profile-id (:id prof)
                :file-id (:id file)
                :is-local true
                :name "testfile"
                :content mfile
                :id (uuid/next)}]

    ;; First try
    (let [{:keys [result error] :as out} (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? error))
      (t/is (= (:id params) (:id result)))
      (t/is (= (:file-id params) (:file-id result)))
      (t/is (= 800 (:width result)))
      (t/is (= 800 (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? (:media-id result)))
      (t/is (uuid? (:thumbnail-id result))))

    ;; Second try
    (let [{:keys [result error] :as out} (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? error))
      (t/is (= (:id params) (:id result)))
      (t/is (= (:file-id params) (:file-id result)))
      (t/is (= 800 (:width result)))
      (t/is (= 800 (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? (:media-id result)))
      (t/is (uuid? (:thumbnail-id result))))))


(t/deftest media-object-from-url-command
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        url    "https://raw.githubusercontent.com/uxbox/uxbox/develop/sample_media/images/unsplash/anna-pelzer.jpg"
        params {::th/type :create-file-media-object-from-url
                ::rpc/profile-id (:id prof)
                :file-id    (:id file)
                :is-local   true
                :url        url}
        out   (th/command! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [{:keys [media-id thumbnail-id] :as result} (:result out)]
      (t/is (= (:id file) (:file-id result)))
      (t/is (= 1024 (:width result)))
      (t/is (= 683  (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? media-id))
      (t/is (uuid? thumbnail-id))
      (let [storage (:app.storage/storage th/*system*)
            mobj1   (sto/get-object storage media-id)
            mobj2   (sto/get-object storage thumbnail-id)]
        (t/is (sto/object? mobj1))
        (t/is (sto/object? mobj2))
        (t/is (= 122785 (:size mobj1)))
        (t/is (= 3297 (:size mobj2)))))))

(t/deftest media-object-upload-command
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        mfile  {:filename "sample.jpg"
                :path (th/tempfile "backend_tests/test_files/sample.jpg")
                :mtype "image/jpeg"
                :size 312043}

        params {::th/type :upload-file-media-object
                ::rpc/profile-id (:id prof)
                :file-id (:id file)
                :is-local true
                :name "testfile"
                :content mfile}
        out    (th/command! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [{:keys [media-id thumbnail-id] :as result} (:result out)]
      (t/is (= (:id file) (:file-id result)))
      (t/is (= 800 (:width result)))
      (t/is (= 800  (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? media-id))
      (t/is (uuid? thumbnail-id))
      (let [storage (:app.storage/storage th/*system*)
            mobj1   (sto/get-object storage media-id)
            mobj2   (sto/get-object storage thumbnail-id)]
        (t/is (sto/object? mobj1))
        (t/is (sto/object? mobj2))
        (t/is (= 312043 (:size mobj1)))
        (t/is (= 3890   (:size mobj2)))))))


(t/deftest media-object-upload-idempotency-command
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        mfile  {:filename "sample.jpg"
                :path (th/tempfile "backend_tests/test_files/sample.jpg")
                :mtype "image/jpeg"
                :size 312043}

        params {::th/type :upload-file-media-object
                ::rpc/profile-id (:id prof)
                :file-id (:id file)
                :is-local true
                :name "testfile"
                :content mfile
                :id (uuid/next)}]

    ;; First try
    (let [{:keys [result error] :as out} (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? error))
      (t/is (= (:id params) (:id result)))
      (t/is (= (:file-id params) (:file-id result)))
      (t/is (= 800 (:width result)))
      (t/is (= 800 (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? (:media-id result)))
      (t/is (uuid? (:thumbnail-id result))))

    ;; Second try
    (let [{:keys [result error] :as out} (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? error))
      (t/is (= (:id params) (:id result)))
      (t/is (= (:file-id params) (:file-id result)))
      (t/is (= 800 (:width result)))
      (t/is (= 800 (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? (:media-id result)))
      (t/is (uuid? (:thumbnail-id result))))))


(t/deftest media-object-upload-command-when-file-is-deleted
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})

        _      (th/db-update! :file
                              {:deleted-at (ct/now)}
                              {:id (:id file)})

        mfile  {:filename "sample.jpg"
                :path (th/tempfile "backend_tests/test_files/sample.jpg")
                :mtype "image/jpeg"
                :size 312043}

        params {::th/type :upload-file-media-object
                ::rpc/profile-id (:id prof)
                :file-id (:id file)
                :is-local true
                :name "testfile"
                :content mfile}

        out    (th/command! params)]

    (let [error      (:error out)
          error-data (ex-data error)]
      (t/is (th/ex-info? error))
      (t/is (= (:type error-data) :not-found)))))


(t/deftest download-image-connection-error
  (t/testing "connection refused raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req!
                            :throw (java.net.ConnectException. "Connection refused")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "http://unreachable.invalid/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "connection timeout raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req!
                            :throw (java.net.http.HttpConnectTimeoutException. "Connect timed out")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "http://unreachable.invalid/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "request timeout raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req!
                            :throw (java.net.http.HttpTimeoutException. "Request timed out")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "http://unreachable.invalid/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "I/O error raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req!
                            :throw (java.io.IOException. "Stream closed")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "http://unreachable.invalid/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err))))))))


(t/deftest download-image-status-code-error
  (t/testing "404 status raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req!
                            :return {:status 404
                                     :headers {"content-type" "text/html"
                                               "content-length" "0"}
                                     :body nil}}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "http://example.com/not-found.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "500 status raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req!
                            :return {:status 500
                                     :headers {"content-type" "text/html"
                                               "content-length" "0"}
                                     :body nil}}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "http://example.com/server-error.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "302 status raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req!
                            :return {:status 302
                                     :headers {"content-type" "text/html"
                                               "content-length" "0"}
                                     :body nil}}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "http://example.com/redirect.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err))))))))
