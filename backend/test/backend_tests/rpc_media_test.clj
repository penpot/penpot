;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-media-test
  (:require
   [app.common.uuid :as uuid]
   [app.http.client :as http]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]])
  (:import
   java.io.RandomAccessFile))

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
                              {:deleted-at (app.common.time/now)}
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
    (with-mocks [http-mock {:target 'app.http.client/req-with-redirects
                            :throw (java.net.ConnectException. "Connection refused")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "https://example.com/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "connection timeout raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req-with-redirects
                            :throw (java.net.http.HttpConnectTimeoutException. "Connect timed out")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "https://example.com/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "request timeout raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req-with-redirects
                            :throw (java.net.http.HttpTimeoutException. "Request timed out")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "https://example.com/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "I/O error raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req-with-redirects
                            :throw (java.io.IOException. "Stream closed")}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "https://example.com/image.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err))))))))


(t/deftest download-image-status-code-error
  (t/testing "404 status raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req-with-redirects
                            :return {:status 404
                                     :headers {"content-type" "text/html"
                                               "content-length" "0"}
                                     :body nil}}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "https://example.com/not-found.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "500 status raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req-with-redirects
                            :return {:status 500
                                     :headers {"content-type" "text/html"
                                               "content-length" "0"}
                                     :body nil}}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "https://example.com/server-error.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err)))))))

  (t/testing "302 status raises validation error"
    (with-mocks [http-mock {:target 'app.http.client/req-with-redirects
                            :return {:status 302
                                     :headers {"content-type" "text/html"
                                               "content-length" "0"}
                                     :body nil}}]
      (let [cfg {::http/client :mock-client}
            err (try
                  (media/download-image cfg "https://example.com/redirect.png")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :unable-to-download-image (:code (ex-data err))))))))

;; --------------------------------------------------------------------
;; Helpers for chunked-upload tests
;; --------------------------------------------------------------------

(defn- split-file-into-chunks
  "Splits the file at `path` into byte-array chunks of at most
  `chunk-size` bytes. Returns a vector of byte arrays."
  [path chunk-size]
  (let [file   (RandomAccessFile. (str path) "r")
        length (.length file)]
    (try
      (loop [offset 0 chunks []]
        (if (>= offset length)
          chunks
          (let [remaining (- length offset)
                size      (min chunk-size remaining)
                buf       (byte-array size)]
            (.seek file offset)
            (.readFully file buf)
            (recur (+ offset size) (conj chunks buf)))))
      (finally
        (.close file)))))

(defn- make-chunk-mfile
  "Writes `data` (byte array) to a tempfile and returns a map
  compatible with `media/schema:upload`."
  [data mtype]
  (let [tmp (fs/create-tempfile :dir "/tmp/penpot" :prefix "test-chunk-")]
    (io/write* tmp data)
    {:filename "chunk"
     :path     tmp
     :mtype    mtype
     :size     (alength data)}))

;; --------------------------------------------------------------------
;; Chunked-upload tests
;; --------------------------------------------------------------------

(defn- create-session!
  "Creates an upload session for `prof` with `total-chunks`. Returns the session-id UUID."
  [prof total-chunks]
  (let [out (th/command! {::th/type        :create-upload-session
                          ::rpc/profile-id (:id prof)
                          :total-chunks    total-chunks})]
    (t/is (nil? (:error out)))
    (:session-id (:result out))))

(t/deftest chunked-upload-happy-path
  (let [prof       (th/create-profile* 1)
        _          (th/create-project* 1 {:profile-id (:id prof)
                                          :team-id (:default-team-id prof)})
        file       (th/create-file* 1 {:profile-id (:id prof)
                                       :project-id (:default-project-id prof)
                                       :is-shared false})
        source-path (th/tempfile "backend_tests/test_files/sample.jpg")
        chunks      (split-file-into-chunks source-path 110000) ; ~107 KB each
        mtype       "image/jpeg"
        total-size  (reduce + (map alength chunks))
        session-id  (create-session! prof (count chunks))]

    (t/is (= 3 (count chunks)))

    ;; --- 1. Upload chunks ---
    (doseq [[idx chunk-data] (map-indexed vector chunks)]
      (let [mfile (make-chunk-mfile chunk-data mtype)
            out   (th/command! {::th/type        :upload-chunk
                                ::rpc/profile-id (:id prof)
                                :session-id      session-id
                                :index           idx
                                :content         mfile})]
        (t/is (nil? (:error out)))
        (t/is (= session-id (:session-id (:result out))))
        (t/is (= idx (:index (:result out))))))

    ;; --- 2. Assemble ---
    (let [assemble-out (th/command! {::th/type        :assemble-file-media-object
                                     ::rpc/profile-id (:id prof)
                                     :session-id      session-id
                                     :file-id         (:id file)
                                     :is-local        true
                                     :name            "assembled-image"
                                     :mtype           mtype})]

      (t/is (nil? (:error assemble-out)))
      (let [{:keys [media-id thumbnail-id] :as result} (:result assemble-out)]
        (t/is (= (:id file) (:file-id result)))
        (t/is (= 800 (:width result)))
        (t/is (= 800 (:height result)))
        (t/is (= mtype (:mtype result)))
        (t/is (uuid? media-id))
        (t/is (uuid? thumbnail-id))

        (let [storage (:app.storage/storage th/*system*)
              mobj1   (sto/get-object storage media-id)
              mobj2   (sto/get-object storage thumbnail-id)]
          (t/is (sto/object? mobj1))
          (t/is (sto/object? mobj2))
          (t/is (= total-size (:size mobj1))))))))

(t/deftest chunked-upload-idempotency
  (let [prof       (th/create-profile* 1)
        _          (th/create-project* 1 {:profile-id (:id prof)
                                          :team-id (:default-team-id prof)})
        file       (th/create-file* 1 {:profile-id (:id prof)
                                       :project-id (:default-project-id prof)
                                       :is-shared false})
        media-id   (uuid/next)
        source-path (th/tempfile "backend_tests/test_files/sample.jpg")
        chunks      (split-file-into-chunks source-path 312043) ; single chunk = whole file
        mtype       "image/jpeg"
        mfile       (make-chunk-mfile (first chunks) mtype)
        session-id  (create-session! prof 1)]

    (th/command! {::th/type        :upload-chunk
                  ::rpc/profile-id (:id prof)
                  :session-id      session-id
                  :index           0
                  :content         mfile})

    ;; First assemble succeeds; session row is deleted afterwards
    (let [out1 (th/command! {::th/type        :assemble-file-media-object
                             ::rpc/profile-id (:id prof)
                             :session-id      session-id
                             :file-id         (:id file)
                             :is-local        true
                             :name            "sample"
                             :mtype           mtype
                             :id              media-id})]
      (t/is (nil? (:error out1)))
      (t/is (= media-id (:id (:result out1)))))

    ;; Second assemble with the same session-id must fail because the
    ;; session row has been deleted after the first assembly
    (let [out2 (th/command! {::th/type        :assemble-file-media-object
                             ::rpc/profile-id (:id prof)
                             :session-id      session-id
                             :file-id         (:id file)
                             :is-local        true
                             :name            "sample"
                             :mtype           mtype
                             :id              media-id})]
      (t/is (some? (:error out2)))
      (t/is (= :not-found (-> out2 :error ex-data :type)))
      (t/is (= :object-not-found (-> out2 :error ex-data :code))))))

(t/deftest chunked-upload-no-permission
  ;; A second profile must not be able to upload chunks into a session
  ;; that belongs to another profile: the DB lookup includes profile-id,
  ;; so the session will not be found.
  (let [prof1      (th/create-profile* 1)
        prof2      (th/create-profile* 2)
        session-id (create-session! prof1 1)
        source-path (th/tempfile "backend_tests/test_files/sample.jpg")
        mfile       {:filename "sample.jpg"
                     :path     source-path
                     :mtype    "image/jpeg"
                     :size     312043}

        ;; prof2 tries to upload a chunk into prof1's session
        out (th/command! {::th/type        :upload-chunk
                          ::rpc/profile-id (:id prof2)
                          :session-id      session-id
                          :index           0
                          :content         mfile})]

    (t/is (some? (:error out)))
    (t/is (= :not-found (-> out :error ex-data :type)))))

(t/deftest chunked-upload-invalid-media-type
  (let [prof       (th/create-profile* 1)
        _          (th/create-project* 1 {:profile-id (:id prof)
                                          :team-id (:default-team-id prof)})
        file       (th/create-file* 1 {:profile-id (:id prof)
                                       :project-id (:default-project-id prof)
                                       :is-shared false})
        session-id (create-session! prof 1)
        source-path (th/tempfile "backend_tests/test_files/sample.jpg")
        mfile       {:filename "sample.jpg"
                     :path     source-path
                     :mtype    "image/jpeg"
                     :size     312043}]

    (th/command! {::th/type        :upload-chunk
                  ::rpc/profile-id (:id prof)
                  :session-id      session-id
                  :index           0
                  :content         mfile})

    ;; Assemble with a wrong mtype should fail validation
    (let [out (th/command! {::th/type        :assemble-file-media-object
                            ::rpc/profile-id (:id prof)
                            :session-id      session-id
                            :file-id         (:id file)
                            :is-local        true
                            :name            "bad-type"
                            :mtype           "application/octet-stream"})]
      (t/is (some? (:error out)))
      (t/is (= :validation (-> out :error ex-data :type))))))

(t/deftest chunked-upload-missing-chunks
  (let [prof       (th/create-profile* 1)
        _          (th/create-project* 1 {:profile-id (:id prof)
                                          :team-id (:default-team-id prof)})
        file       (th/create-file* 1 {:profile-id (:id prof)
                                       :project-id (:default-project-id prof)
                                       :is-shared false})
        ;; Session expects 3 chunks
        session-id (create-session! prof 3)
        source-path (th/tempfile "backend_tests/test_files/sample.jpg")
        mfile       {:filename "sample.jpg"
                     :path     source-path
                     :mtype    "image/jpeg"
                     :size     312043}]

    ;; Upload only 1 chunk
    (th/command! {::th/type        :upload-chunk
                  ::rpc/profile-id (:id prof)
                  :session-id      session-id
                  :index           0
                  :content         mfile})

    ;; Assemble: session says 3 expected, only 1 stored → :missing-chunks
    (let [out (th/command! {::th/type        :assemble-file-media-object
                            ::rpc/profile-id (:id prof)
                            :session-id      session-id
                            :file-id         (:id file)
                            :is-local        true
                            :name            "incomplete"
                            :mtype           "image/jpeg"})]
      (t/is (some? (:error out)))
      (t/is (= :validation (-> out :error ex-data :type)))
      (t/is (= :missing-chunks (-> out :error ex-data :code))))))

(t/deftest chunked-upload-session-not-found
  (let [prof       (th/create-profile* 1)
        _          (th/create-project* 1 {:profile-id (:id prof)
                                          :team-id (:default-team-id prof)})
        file       (th/create-file* 1 {:profile-id (:id prof)
                                       :project-id (:default-project-id prof)
                                       :is-shared false})
        bogus-id   (uuid/next)]

    ;; Assemble with a session-id that was never created
    (let [out (th/command! {::th/type        :assemble-file-media-object
                            ::rpc/profile-id (:id prof)
                            :session-id      bogus-id
                            :file-id         (:id file)
                            :is-local        true
                            :name            "ghost"
                            :mtype           "image/jpeg"})]
      (t/is (some? (:error out)))
      (t/is (= :not-found (-> out :error ex-data :type)))
      (t/is (= :object-not-found (-> out :error ex-data :code))))))

(t/deftest chunked-upload-over-chunk-limit
  ;; Verify that requesting more chunks than the configured maximum
  ;; (quotes-upload-chunks-per-session) raises a :restriction error.
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-upload-chunks-per-session 3})}]
    (let [prof (th/create-profile* 1)
          out  (th/command! {::th/type        :create-upload-session
                             ::rpc/profile-id (:id prof)
                             :total-chunks    4})]

      (t/is (some? (:error out)))
      (t/is (= :restriction (-> out :error ex-data :type)))
      (t/is (= :max-quote-reached (-> out :error ex-data :code)))
      (t/is (= "upload-chunks-per-session" (-> out :error ex-data :target))))))

(t/deftest chunked-upload-invalid-chunk-index
  ;; Both a negative index and an index >= total-chunks must be
  ;; rejected with a :validation / :invalid-chunk-index error.
  (let [prof (th/create-profile* 1)
        session-id   (create-session! prof 2)
        source-path  (th/tempfile "backend_tests/test_files/sample.jpg")
        mfile        {:filename "sample.jpg"
                      :path     source-path
                      :mtype    "image/jpeg"
                      :size     312043}]

    ;; index == total-chunks (out of range)
    (let [out (th/command! {::th/type        :upload-chunk
                            ::rpc/profile-id (:id prof)
                            :session-id      session-id
                            :index           2
                            :content         mfile})]
      (t/is (some? (:error out)))
      (t/is (= :validation (-> out :error ex-data :type)))
      (t/is (= :invalid-chunk-index (-> out :error ex-data :code))))

    ;; negative index
    (let [out (th/command! {::th/type        :upload-chunk
                            ::rpc/profile-id (:id prof)
                            :session-id      session-id
                            :index           -1
                            :content         mfile})]
      (t/is (some? (:error out)))
      (t/is (= :validation (-> out :error ex-data :type)))
      (t/is (= :invalid-chunk-index (-> out :error ex-data :code))))))

(t/deftest chunked-upload-sessions-per-profile-quota
  ;; With the session limit set to 2, creating a third session for the
  ;; same profile must fail with :restriction / :max-quote-reached.
  ;; The :quotes flag is already enabled by the test fixture.
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-upload-sessions-per-profile 2})}]
    (let [prof (th/create-profile* 1)]

      ;; First two sessions succeed
      (create-session! prof 1)
      (create-session! prof 1)

      ;; Third session must be rejected
      (let [out (th/command! {::th/type        :create-upload-session
                              ::rpc/profile-id (:id prof)
                              :total-chunks    1})]
        (t/is (some? (:error out)))
        (t/is (= :restriction (-> out :error ex-data :type)))
        (t/is (= :max-quote-reached (-> out :error ex-data :code)))))))
