;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.media-validation-test
  (:require
   [app.media.validation :as media.v]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

(t/deftest check-media-type
  (t/testing "allowed mtype passes through"
    (let [upload {:filename "a.png" :size 1 :path (fs/path "/tmp/penpot/a.png") :mtype "image/png"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "disallowed mtype is rejected"
    (let [upload {:filename "a.txt" :size 1 :path (fs/path "/tmp/penpot/a.txt") :mtype "text/plain"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-not-allowed)))))

(t/deftest check-media-size
  (t/testing "small file passes through"
    (let [upload {:filename "a.png" :size 1 :path (fs/path "/tmp/penpot/a.png") :mtype "image/png"}]
      (t/is (= upload (media.v/check-media-size upload)))))
  (t/testing "oversized file is rejected"
    (let [upload {:filename "a.png" :size (* 1024 1024 31) :path (fs/path "/tmp/penpot/a.png") :mtype "image/png"}
          error  (try (media.v/check-media-size upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :restriction))
      (t/is (th/ex-of-code? error :media-max-file-size-reached)))))

(t/deftest check-font-size
  (t/testing "small font passes through"
    (let [upload {:filename "a.ttf" :size 1 :path (fs/path "/tmp/penpot/a.ttf") :mtype "font/ttf"}]
      (t/is (= upload (media.v/check-font-size upload)))))
  (t/testing "oversized font is rejected"
    (let [upload {:filename "a.ttf" :size (* 1024 1024 31) :path (fs/path "/tmp/penpot/a.ttf") :mtype "font/ttf"}
          error  (try (media.v/check-font-size upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :restriction))
      (t/is (th/ex-of-code? error :font-max-file-size-reached)))))

(t/deftest check-upload-path
  (t/testing "tempfile under /tmp/penpot passes through"
    (let [path   (th/tempfile "backend_tests/test_files/sample.jpg")
          upload {:filename "sample.jpg" :size 7923 :path path :mtype "image/jpeg"}]
      (t/is (= upload (media.v/check-upload-path upload)))))
  (t/testing "system file outside temp is rejected"
    (let [upload {:filename "x.png" :size 1 :path (fs/path "/etc/passwd") :mtype "image/png"}
          error  (try (media.v/check-upload-path upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :params-validation))))
  (t/testing "missing file is rejected"
    (let [upload {:filename "x.png" :size 1 :path (fs/path "/tmp/penpot/no-such-file-12345.png") :mtype "image/png"}
          error  (try (media.v/check-upload-path upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :params-validation)))))
