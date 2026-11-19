;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.media-validation-test
  (:require
   [app.common.media :as cm]
   [app.media.validation :as media.v]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [datoteka.io :as io]))

(t/deftest check-media-type
  (t/testing "matching png passes through"
    (let [path   (th/tempfile "backend_tests/test_files/sample.png")
          upload {:filename "sample.png" :size 1234 :path path :mtype "image/png"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "matching jpeg passes through"
    (let [path   (th/tempfile "backend_tests/test_files/sample.jpg")
          upload {:filename "sample.jpg" :size 7923 :path path :mtype "image/jpeg"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "svg with prolog passes through"
    (let [path   (th/tempfile "backend_tests/test_files/sample2.svg")
          upload {:filename "sample.svg" :size 1234 :path path :mtype "image/svg+xml"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "svg with comment and doctype passes through"
    (let [path   (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-svg-")
          _      (spit (str path) (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                       "<!-- a comment -->\n"
                                       "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" "
                                       "\"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n"
                                       (slurp (io/resource "backend_tests/test_files/sample1.svg"))))
          upload {:filename "sample.svg" :size 1234 :path path :mtype "image/svg+xml"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "svg with utf-8 comment passes through"
    (let [path   (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-usvg-")
          _      (spit (str path) (str "<!-- café con ñ y emojis \uD83D\uDE00 -->\n"
                                       (slurp (io/resource "backend_tests/test_files/sample1.svg"))))
          upload {:filename "sample.svg" :size 1234 :path path :mtype "image/svg+xml"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "matching bom svg with prolog passes through"
    (let [path   (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-bom-svg-")
          _      (spit (str path) (str "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                       "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"))
          upload {:filename "bom.svg" :size 1234 :path path :mtype "image/svg+xml"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "matching bom svg without prolog passes through"
    (let [path   (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-bom-svg2-")
          _      (spit (str path) (str "\uFEFF<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"))
          upload {:filename "bom2.svg" :size 1234 :path path :mtype "image/svg+xml"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "bom plain text declared as svg is rejected"
    (let [path   (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-bom-txt-")
          _      (spit (str path) "\uFEFFjust some text, no markup at all")
          upload {:filename "evil.svg" :size 1234 :path path :mtype "image/svg+xml"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-mismatch))))
  (t/testing "plain text declared as svg is rejected"
    (let [path   (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-txt-")
          _      (spit (str path) "just some text, no markup at all")
          upload {:filename "evil.svg" :size 1234 :path path :mtype "image/svg+xml"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-mismatch))))
  (t/testing "uppercase svg root is rejected"
    (let [path   (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-usvg-")
          _      (spit (str path) "<SVG xmlns=\"http://www.w3.org/2000/svg\"></SVG>")
          upload {:filename "upper.svg" :size 1234 :path path :mtype "image/svg+xml"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-mismatch))))
  (t/testing "matching font passes through"
    (let [path   (th/tempfile "backend_tests/test_files/font-1.ttf")
          upload {:filename "font.ttf" :size 1234 :path path :mtype "font/ttf"}]
      (t/is (= upload (media.v/check-media-type upload cm/font-types)))))
  (t/testing "matching woff passes through"
    (let [path   (th/tempfile "backend_tests/test_files/font-1.woff")
          upload {:filename "font.woff" :size 1234 :path path :mtype "font/woff"}]
      (t/is (= upload (media.v/check-media-type upload cm/font-types)))))
  (t/testing "matching woff2 passes through"
    (let [path   (th/tempfile "backend_tests/test_files/font-1.woff2")
          upload {:filename "font.woff2" :size 1234 :path path :mtype "font/woff2"}]
      (t/is (= upload (media.v/check-media-type upload cm/font-types)))))
  (t/testing "matching otf passes through"
    (let [path   (th/tempfile "backend_tests/test_files/font-1.otf")
          upload {:filename "font.otf" :size 1234 :path path :mtype "font/otf"}]
      (t/is (= upload (media.v/check-media-type upload cm/font-types)))))
  (t/testing "matching webp passes through"
    (let [path   (th/tempfile "backend_tests/test_files/sample.webp")
          upload {:filename "sample.webp" :size 1234 :path path :mtype "image/webp"}]
      (t/is (= upload (media.v/check-media-type upload)))))
  (t/testing "png bytes declared as jpeg are rejected"
    (let [path   (th/tempfile "backend_tests/test_files/sample.png")
          upload {:filename "sample.png" :size 1234 :path path :mtype "image/jpeg"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-mismatch))))
  (t/testing "octet-stream declared for png is rejected"
    (let [path   (th/tempfile "backend_tests/test_files/sample.png")
          upload {:filename "sample.png" :size 1234 :path path :mtype "application/octet-stream"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-not-allowed))))
  (t/testing "png bytes declared as svg are rejected"
    (let [path   (th/tempfile "backend_tests/test_files/sample.png")
          upload {:filename "sample.png" :size 1234 :path path :mtype "image/svg+xml"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-mismatch))))
  (t/testing "disallowed real type is rejected"
    (let [path   (th/tempfile "backend_tests/test_files/file-with-library.penpot")
          upload {:filename "bundle.penpot" :size 1234 :path path :mtype "application/zip"}
          error  (try (media.v/check-media-type upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :media-type-not-allowed)))))

(defn- sparse-file!
  "Grow the file at `path` to `size` bytes without writing them. The
  file stays sparse so no disk is used, while `fs/size` still reports
  the full length."
  [path size]
  (with-open [raf (java.io.RandomAccessFile. (str path) "rw")]
    (.setLength raf size))
  path)

(t/deftest check-media-size
  (t/testing "small file passes even when the declared size lies upwards"
    (let [path   (th/tempfile "backend_tests/test_files/sample.jpg")
          upload {:filename "sample.jpg" :size (* 1024 1024 31) :path path :mtype "image/jpeg"}]
      (t/is (= upload (media.v/check-media-size upload)))))
  (t/testing "file over the limit is rejected even when declared size lies downwards"
    (let [path   (sparse-file! (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-big-")
                               (* 1024 1024 31))
          upload {:filename "big.jpg" :size 1 :path path :mtype "image/jpeg"}
          error  (try (media.v/check-media-size upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :restriction))
      (t/is (th/ex-of-code? error :media-max-file-size-reached))))
  (t/testing "missing file fails closed with the path code"
    (let [upload {:filename "gone.jpg" :size 1 :path (fs/path "/tmp/penpot/no-such-file-12345.jpg")
                  :mtype "image/jpeg"}
          error  (try (media.v/check-media-size upload) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :params-validation)))))

(t/deftest check-font-size
  (t/testing "small font passes even when the declared size lies upwards"
    (let [path   (th/tempfile "backend_tests/test_files/font-1.ttf")
          upload {:filename "font.ttf" :size (* 1024 1024 31) :path path :mtype "font/ttf"}]
      (t/is (= upload (media.v/check-font-size upload)))))
  (t/testing "font over the limit is rejected even when declared size lies downwards"
    (let [path   (sparse-file! (fs/create-tempfile :dir "/tmp/penpot" :prefix "penpot-test-big-font-")
                               (* 1024 1024 31))
          upload {:filename "big.ttf" :size 1 :path path :mtype "font/ttf"}
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
