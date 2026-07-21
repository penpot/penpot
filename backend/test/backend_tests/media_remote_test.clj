;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.media-remote-test
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.media.remote :as media.remote]
   [app.setup :as-alias setup]
   [app.util.json :as json]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]])
  (:import
   java.io.ByteArrayInputStream))

(defn- mk-system
  "Minimal system map for media.remote/process tests."
  []
  {::setup/shared-keys {:media-processor "test-shared-key"}})

(defn- json-stream
  "Create an InputStream from a Clojure data structure (JSON-encoded)."
  [data]
  (ByteArrayInputStream.
   (json/encode data)))

(def config-mock
  "Standard config mock for media-processor service."
  {:media-processing-service-uri "http://localhost:6065"
   :media-processing-service-timeout 5000})

(defn- write-font-tmp
  "Write font bytes to a tempfile and return the Path. Caller is responsible for cleanup."
  [bytes suffix]
  (let [tmp (fs/create-tempfile :prefix "penpot-test-font-" :suffix suffix)]
    (io/write* tmp bytes)
    tmp))

;; ---------------------------------------------------------------------------
;; :info
;; ---------------------------------------------------------------------------

(t/deftest info-happy-path
  (t/testing "info returns dimensions and merges into input"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :return {:status 200
                                :body (json-stream {:width 800 :height 600})}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path   (th/tempfile "backend_tests/test_files/sample.jpg")
              result (media.remote/process (mk-system)
                                           {:cmd   :info
                                            :input {:path path :mtype "image/jpeg"}})]
          (t/is (= 800 (:width result)))
          (t/is (= 600 (:height result)))
          (t/is (= (fs/size path) (:size result)))
          (t/is (some? (:ts result)))
          (t/is (= path (:path result)))
          (t/is (= "image/jpeg" (:mtype result)))
          (t/is (= 1 (:call-count @mock))))))))

(t/deftest info-verifies-request-params
  (t/testing "info sends correct endpoint, method, and x-shared-key header"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :return {:status 200
                                :body (json-stream {:width 100 :height 100 :size 1})}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path (th/tempfile "backend_tests/test_files/sample.jpg")]
          (media.remote/process (mk-system)
                                {:cmd :info :input {:path path :mtype "image/jpeg"}})
          (let [[system req-map] (:call-args @mock)]
            ;; System passed through
            (t/is (some? (::setup/shared-keys system)))
            ;; Request structure
            (t/is (= :post (:method req-map)))
            (t/is (str/includes? (str (:uri req-map)) "api/image/info"))
            (t/is (= "test-shared-key" (get-in req-map [:headers "x-shared-key"])))
            (t/is (str/starts-with?
                   (get-in req-map [:headers "Content-Type"])
                   "multipart/form-data"))))))))

(t/deftest info-service-uri-not-configured
  (t/testing "info throws when service URI is not configured"
    (with-redefs [cf/get (th/config-get-mock {})]
      (let [path (th/tempfile "backend_tests/test_files/sample.jpg")
            err  (ex/try! (media.remote/process (mk-system)
                                                {:cmd :info :input {:path path :mtype "image/jpeg"}}))]
        (t/is (ex/error? err))
        (t/is (= :internal (:type (ex-data err))))
        (t/is (= :media-processor-not-configured (:code (ex-data err))))))))

(t/deftest info-service-unavailable
  (t/testing "info throws when service-request raises unavailable"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :throw (ex-info "Cannot connect to media-processor service"
                                       {:type :internal
                                        :code :media-processor-unavailable})}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path (th/tempfile "backend_tests/test_files/sample.jpg")
              err  (ex/try! (media.remote/process (mk-system)
                                                  {:cmd :info :input {:path path :mtype "image/jpeg"}}))]
          (t/is (ex/error? err))
          (t/is (= :internal (:type (ex-data err))))
          (t/is (= :media-processor-unavailable (:code (ex-data err)))))))))

(t/deftest info-service-timeout
  (t/testing "info throws when service-request raises timeout"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :throw (ex-info "media-processor service request timed out"
                                       {:type :internal
                                        :code :media-processor-timeout})}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path (th/tempfile "backend_tests/test_files/sample.jpg")
              err  (ex/try! (media.remote/process (mk-system)
                                                  {:cmd :info :input {:path path :mtype "image/jpeg"}}))]
          (t/is (ex/error? err))
          (t/is (= :internal (:type (ex-data err))))
          (t/is (= :media-processor-timeout (:code (ex-data err)))))))))

(t/deftest info-mtype-mismatch
  (t/testing "info raises :media-type-mismatch when detected mtype differs from declared"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :return {:status 200
                                :body (json-stream {:width 100 :height 100 :size 100
                                                    :mtype "image/png"})}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path (th/tempfile "backend_tests/test_files/sample.jpg")
              err  (ex/try! (media.remote/process (mk-system)
                                                  {:cmd   :info
                                                   :input {:path path :mtype "image/jpeg"}}))]
          (t/is (ex/error? err))
          (t/is (= :validation (:type (ex-data err))))
          (t/is (= :media-type-mismatch (:code (ex-data err)))))))))

;; ---------------------------------------------------------------------------
;; :generic-thumbnail
;; ---------------------------------------------------------------------------

(t/deftest generic-thumbnail-happy-path
  (t/testing "generic-thumbnail returns tempfile with correct format"
    (let [thumb-bytes (.getBytes "fake-jpeg-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. thumb-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (let [path   (th/tempfile "backend_tests/test_files/sample.jpg")
                result (media.remote/process (mk-system)
                                             {:cmd    :generic-thumbnail
                                              :input  {:path path :mtype "image/jpeg"}
                                              :format :jpeg
                                              :quality 80
                                              :width  200
                                              :height 200})]
            (t/is (= :jpeg (:format result)))
            (t/is (= "image/jpeg" (:mtype result)))
            (t/is (pos? (:size result)))
            (t/is (fs/exists? (:data result)))))))))

(t/deftest generic-thumbnail-verifies-query-params
  (t/testing "generic-thumbnail sends correct query params with mode=fit"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :return {:status 200
                                :body (ByteArrayInputStream. (.getBytes "data" "UTF-8"))}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path (th/tempfile "backend_tests/test_files/sample.jpg")]
          (media.remote/process (mk-system)
                                {:cmd    :generic-thumbnail
                                 :input  {:path path :mtype "image/jpeg"}
                                 :format :jpeg
                                 :quality 85
                                 :width  300
                                 :height 400})
          (let [[_ req-map] (:call-args @mock)]
            (t/is (str/includes? (str (:uri req-map)) "width=300"))
            (t/is (str/includes? (str (:uri req-map)) "height=400"))
            (t/is (str/includes? (str (:uri req-map)) "quality=85"))
            (t/is (str/includes? (str (:uri req-map)) "format=jpeg"))
            (t/is (str/includes? (str (:uri req-map)) "mode=fit"))))))))

(t/deftest generic-thumbnail-service-unavailable
  (t/testing "generic-thumbnail throws on service error"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :throw (ex-info "Cannot connect to media-processor service"
                                       {:type :internal
                                        :code :media-processor-unavailable})}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path (th/tempfile "backend_tests/test_files/sample.jpg")
              err  (ex/try! (media.remote/process (mk-system)
                                                  {:cmd    :generic-thumbnail
                                                   :input  {:path path :mtype "image/jpeg"}
                                                   :format :jpeg
                                                   :quality 85
                                                   :width  200
                                                   :height 200}))]
          (t/is (ex/error? err))
          (t/is (= :media-processor-unavailable (:code (ex-data err)))))))))

;; ---------------------------------------------------------------------------
;; :profile-thumbnail
;; ---------------------------------------------------------------------------

(t/deftest profile-thumbnail-happy-path
  (t/testing "profile-thumbnail returns tempfile and uses mode=crop"
    (let [thumb-bytes (.getBytes "fake-png-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. thumb-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (let [path   (th/tempfile "backend_tests/test_files/sample.jpg")
                result (media.remote/process (mk-system)
                                             {:cmd    :profile-thumbnail
                                              :input  {:path path :mtype "image/jpeg"}
                                              :format :jpeg
                                              :quality 85
                                              :width  128
                                              :height 128})]
            (t/is (some? (:data result)))
            (t/is (fs/exists? (:data result)))
            ;; Verify mode=crop in URI
            (let [[_ req-map] (:call-args @mock)]
              (t/is (str/includes? (str (:uri req-map)) "mode=crop")))))))))

(t/deftest profile-thumbnail-service-unavailable
  (t/testing "profile-thumbnail throws on service error"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :throw (ex-info "Cannot connect to media-processor service"
                                       {:type :internal
                                        :code :media-processor-unavailable})}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [path (th/tempfile "backend_tests/test_files/sample.jpg")
              err  (ex/try! (media.remote/process (mk-system)
                                                  {:cmd    :profile-thumbnail
                                                   :input  {:path path :mtype "image/jpeg"}
                                                   :format :jpeg
                                                   :quality 85
                                                   :width  128
                                                   :height 128}))]
          (t/is (ex/error? err))
          (t/is (= :media-processor-unavailable (:code (ex-data err)))))))))

;; ---------------------------------------------------------------------------
;; :generate-fonts
;; ---------------------------------------------------------------------------

(t/deftest generate-fonts-ttf-happy-path
  (t/testing "generate-fonts with TTF path makes per-variant calls"
    (let [ttfbytes   (io/read* (io/resource "backend_tests/test_files/font-1.ttf"))
          ttfpath    (write-font-tmp ttfbytes ".ttf")
          fake-bytes (.getBytes "fake-font-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. fake-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (let [result (media.remote/process (mk-system)
                                               {:cmd   :generate-fonts
                                                :input {"font/ttf" ttfpath}})]
              ;; Original path preserved
              (t/is (= ttfpath (get result "font/ttf")))
              ;; Variants written to tempfiles
              (t/is (fs/exists? (get result "font/otf")))
              (t/is (fs/exists? (get result "font/woff")))
              ;; Two calls: one for otf, one for woff
              (t/is (= 2 (:call-count @mock))))
            (finally
              (fs/delete ttfpath))))))))

(t/deftest generate-fonts-ttf-as-path
  (t/testing "generate-fonts with TTF as tempfile Path works"
    (let [ttfbytes   (io/read* (io/resource "backend_tests/test_files/font-1.ttf"))
          tmp-path   (write-font-tmp ttfbytes ".ttf")
          fake-bytes (.getBytes "fake-font-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. fake-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (let [result (media.remote/process (mk-system)
                                               {:cmd   :generate-fonts
                                                :input {"font/ttf" tmp-path}})]
              ;; Path preserved
              (t/is (= tmp-path (get result "font/ttf")))
              ;; Variant written
              (t/is (fs/exists? (get result "font/otf"))))
            (finally
              (fs/delete tmp-path))))))))

(t/deftest generate-fonts-otf-happy-path
  (t/testing "generate-fonts with OTF path"
    (let [otfbytes   (io/read* (io/resource "backend_tests/test_files/font-1.otf"))
          otfpath    (write-font-tmp otfbytes ".otf")
          fake-bytes (.getBytes "fake-font-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. fake-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (let [result (media.remote/process (mk-system)
                                               {:cmd   :generate-fonts
                                                :input {"font/otf" otfpath}})]
              (t/is (= otfpath (get result "font/otf")))
              (t/is (fs/exists? (get result "font/ttf")))
              (t/is (fs/exists? (get result "font/woff")))
              ;; Two calls: one for ttf, one for woff
              (t/is (= 2 (:call-count @mock))))
            (finally
              (fs/delete otfpath))))))))

(t/deftest generate-fonts-woff-happy-path
  (t/testing "generate-fonts with WOFF path"
    (let [woffbytes  (io/read* (io/resource "backend_tests/test_files/font-1.woff"))
          woffpath   (write-font-tmp woffbytes ".woff")
          fake-bytes (.getBytes "fake-font-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. fake-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (let [result (media.remote/process (mk-system)
                                               {:cmd   :generate-fonts
                                                :input {"font/woff" woffpath}})]
              (t/is (= woffpath (get result "font/woff")))
              (t/is (fs/exists? (get result "font/ttf")))
              (t/is (fs/exists? (get result "font/otf")))
              ;; Two calls: one for ttf, one for otf
              (t/is (= 2 (:call-count @mock))))
            (finally
              (fs/delete woffpath)))))))

  (t/deftest generate-fonts-woff2-happy-path
    (t/testing "generate-fonts with WOFF2 path"
      (let [woff2bytes (io/read* (io/resource "backend_tests/test_files/font-1.woff2"))
            woff2path  (write-font-tmp woff2bytes ".woff2")
            fake-bytes (.getBytes "fake-font-data" "UTF-8")]
        (with-mocks [mock {:target 'app.media.remote/service-request
                           :return {:status 200
                                    :body (ByteArrayInputStream. fake-bytes)}}]
          (with-redefs [cf/get (th/config-get-mock config-mock)]
            (try
              (let [result (media.remote/process (mk-system)
                                                 {:cmd   :generate-fonts
                                                  :input {"font/woff2" woff2path}})]
                (t/is (= woff2path (get result "font/woff2")))
                (t/is (fs/exists? (get result "font/ttf")))
                (t/is (fs/exists? (get result "font/otf")))
                (t/is (fs/exists? (get result "font/woff")))
                ;; Three calls: one for ttf, one for otf, one for woff
                (t/is (= 3 (:call-count @mock))))
              (finally
                (fs/delete woff2path)))))))))

(t/deftest generate-fonts-verifies-query-params
  (t/testing "generate-fonts sends target-type query param with 180s timeout"
    (let [ttfbytes  (io/read* (io/resource "backend_tests/test_files/font-1.ttf"))
          ttfpath   (write-font-tmp ttfbytes ".ttf")
          fake-bytes (.getBytes "fake-font-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. fake-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (media.remote/process (mk-system)
                                  {:cmd :generate-fonts :input {"font/ttf" ttfpath}})
            (let [[_ req-map] (:call-args @mock)]
              (t/is (str/includes? (str (:uri req-map)) "target-type="))
              (t/is (= 180000 (:timeout req-map))))
            (finally
              (fs/delete ttfpath))))))))

(t/deftest generate-fonts-woff-verifies-target-types
  (t/testing "generate-fonts with WOFF sends target-type query param"
    (let [woffbytes  (io/read* (io/resource "backend_tests/test_files/font-1.woff"))
          woffpath   (write-font-tmp woffbytes ".woff")
          fake-bytes (.getBytes "fake-font-data" "UTF-8")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :return {:status 200
                                  :body (ByteArrayInputStream. fake-bytes)}}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (media.remote/process (mk-system)
                                  {:cmd :generate-fonts :input {"font/woff" woffpath}})
            (let [[_ req-map] (:call-args @mock)]
              (t/is (str/includes? (str (:uri req-map)) "target-type=")))
            (finally
              (fs/delete woffpath))))))))

(t/deftest generate-fonts-no-recognized-variant
  (t/testing "generate-fonts throws when no recognized font variant"
    (with-redefs [cf/get (th/config-get-mock config-mock)]
      (let [err (ex/try! (media.remote/process (mk-system)
                                               {:cmd   :generate-fonts
                                                :input {"font/unknown" (.getBytes "data" "UTF-8")}}))]
        (t/is (ex/error? err))
        (t/is (= :validation (:type (ex-data err))))
        (t/is (= :invalid-font (:code (ex-data err))))))))

(t/deftest generate-fonts-connection-error
  (t/testing "generate-fonts throws on service error"
    (let [ttfbytes (io/read* (io/resource "backend_tests/test_files/font-1.ttf"))
          ttfpath  (write-font-tmp ttfbytes ".ttf")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :throw (ex-info "Cannot connect to media-processor service"
                                         {:type :internal
                                          :code :media-processor-unavailable})}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (let [err (ex/try! (media.remote/process (mk-system)
                                                     {:cmd :generate-fonts :input {"font/ttf" ttfpath}}))]
              (t/is (ex/error? err))
              (t/is (= :media-processor-unavailable (:code (ex-data err)))))
            (finally
              (fs/delete ttfpath))))))))

(t/deftest generate-fonts-timeout-error
  (t/testing "generate-fonts throws on service timeout"
    (let [ttfbytes (io/read* (io/resource "backend_tests/test_files/font-1.ttf"))
          ttfpath  (write-font-tmp ttfbytes ".ttf")]
      (with-mocks [mock {:target 'app.media.remote/service-request
                         :throw (ex-info "media-processor service request timed out"
                                         {:type :internal
                                          :code :media-processor-timeout})}]
        (with-redefs [cf/get (th/config-get-mock config-mock)]
          (try
            (let [err (ex/try! (media.remote/process (mk-system)
                                                     {:cmd :generate-fonts :input {"font/ttf" ttfpath}}))]
              (t/is (ex/error? err))
              (t/is (= :media-processor-timeout (:code (ex-data err)))))
            (finally
              (fs/delete ttfpath))))))))

;; ---------------------------------------------------------------------------
;; Status code handling (service-request)
;; ---------------------------------------------------------------------------

(t/deftest service-request-raises-on-400
  (t/testing "service-request raises :validation on status 400"
    (with-mocks [mock {:target 'app.http.client/req
                       :return {:status 400
                                :body (json-stream {:type "validation"
                                                    :code "invalid-image"
                                                    :hint "bad input"})}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [err (ex/try! (media.remote/service-request
                            (mk-system)
                            {:method :post
                             :uri "http://localhost:6065/api/image/info"
                             :body nil
                             :headers {}}))]
          (t/is (ex/error? err))
          (t/is (= :validation (:type (ex-data err))))
          (t/is (= :invalid-image (:code (ex-data err)))))))))

(t/deftest service-request-raises-on-500
  (t/testing "service-request raises :internal on status 500"
    (with-mocks [mock {:target 'app.http.client/req
                       :return {:status 500
                                :body (json-stream {:type "internal"
                                                    :code "processing-error"
                                                    :hint "Internal server error"})}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [err (ex/try! (media.remote/service-request
                            (mk-system)
                            {:method :post
                             :uri "http://localhost:6065/api/image/info"
                             :body nil
                             :headers {}}))]
          (t/is (ex/error? err))
          (t/is (= :internal (:type (ex-data err))))
          (t/is (= :processing-error (:code (ex-data err)))))))))

(t/deftest service-request-passes-on-200
  (t/testing "service-request returns response on status 200"
    (with-mocks [mock {:target 'app.http.client/req
                       :return {:status 200
                                :body (json-stream {:width 100 :height 100})}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [resp (media.remote/service-request
                    (mk-system)
                    {:method :post
                     :uri "http://localhost:6065/api/image/info"
                     :body nil
                     :headers {}})]
          (t/is (= 200 (:status resp))))))))

;; ---------------------------------------------------------------------------
;; Shared key
;; ---------------------------------------------------------------------------

(t/deftest shared-key-sent-correctly
  (t/testing "x-shared-key header matches the system's shared key"
    (with-mocks [mock {:target 'app.media.remote/service-request
                       :return {:status 200
                                :body (json-stream {:width 1 :height 1 :size 1})}}]
      (with-redefs [cf/get (th/config-get-mock config-mock)]
        (let [system {::setup/shared-keys {:media-processor "my-secret-key-123"}}]
          (media.remote/process system
                                {:cmd   :info
                                 :input {:path (th/tempfile "backend_tests/test_files/sample.jpg")
                                         :mtype "image/jpeg"}})
          (let [[system-arg _] (:call-args @mock)]
            ;; System passed through correctly
            (t/is (= "my-secret-key-123"
                     (-> system-arg ::setup/shared-keys :media-processor)))))))))
