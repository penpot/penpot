;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.media-test
  (:require
   [app.common.exceptions :as ex]
   [app.media :as media]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)

(t/deftest info-jpeg
  (t/testing "info on valid JPEG returns dimensions and mime type"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info)))
      (t/is (= "image/jpeg" (:mtype info)))
      (t/is (pos? (:size info)))
      (t/is (some? (:ts info))))))

(t/deftest info-png
  (t/testing "info on valid PNG returns dimensions and mime type"
    (let [path  (th/tempfile "backend_tests/test_files/sample.png")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/png"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info)))
      (t/is (= "image/png" (:mtype info))))))

(t/deftest info-webp
  (t/testing "info on valid WebP returns dimensions and mime type"
    (let [path  (th/tempfile "backend_tests/test_files/sample.webp")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/webp"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info)))
      (t/is (= "image/webp" (:mtype info))))))

(t/deftest info-svg
  (t/testing "info on valid SVG returns dimensions from viewBox"
    (let [path  (th/tempfile "backend_tests/test_files/sample1.svg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/svg+xml"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info))))))

(t/deftest info-invalid-image
  (t/testing "info on invalid image raises error"
    (let [path (fs/create-tempfile :prefix "penpot-test-" :suffix ".jpg")]
      ;; Write garbage data
      (spit (str path) "not an image")
      (try
        (media/run th/*system* {:cmd :info
                                :input {:path path
                                        :mtype "image/jpeg"}})
        (t/is false "should have thrown")
        (catch Exception e
          (let [data (ex-data e)]
            ;; Could be validation or imagemagick-error depending on what magick does
            (t/is (contains? #{:validation :internal} (:type data)))))
        (finally
          (fs/delete path))))))

(t/deftest generic-thumbnail
  (t/testing "generic-thumbnail produces a file of expected format"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})
          thumb (media/run th/*system* {:cmd :generic-thumbnail
                                        :input info
                                        :format :jpeg
                                        :quality 80
                                        :width 200
                                        :height 200})]
      (t/is (some? (:data thumb)))
      (t/is (pos? (:size thumb)))
      (t/is (= :jpeg (:format thumb)))
      (t/is (= "image/jpeg" (:mtype thumb)))
      ;; Verify the thumbnail file exists
      (t/is (fs/exists? (:data thumb))))))

(t/deftest profile-thumbnail
  (t/testing "profile-thumbnail produces a center-cropped file"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})
          thumb (media/run th/*system* {:cmd :profile-thumbnail
                                        :input info
                                        :format :jpeg
                                        :quality 85
                                        :width 128
                                        :height 128})]
      (t/is (some? (:data thumb)))
      (t/is (pos? (:size thumb)))
      (t/is (= :jpeg (:format thumb)))
      (t/is (= "image/jpeg" (:mtype thumb)))
      ;; Verify the thumbnail file exists
      (t/is (fs/exists? (:data thumb))))))

(t/deftest generic-thumbnail-webp
  (t/testing "generic-thumbnail can produce WebP format"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})
          thumb (media/run th/*system* {:cmd :generic-thumbnail
                                        :input info
                                        :format :webp
                                        :quality 80
                                        :width 200
                                        :height 200})]
      (t/is (= :webp (:format thumb)))
      (t/is (= "image/webp" (:mtype thumb))))))
