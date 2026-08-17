;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.media-test
  (:require
   [app.common.exceptions :as ex]
   [app.media :as media]
   [app.media.svg :as svg]
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

(t/deftest sanitize-svg-script-tag
  (t/testing "sanitize-svg removes script tags"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><script>alert('xss')</script><rect width=\"50\" height=\"50\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (clojure.string/includes? result "<script>")))
      (t/is (not (clojure.string/includes? result "alert")))
      (t/is (clojure.string/includes? result "<rect")))))

(t/deftest sanitize-svg-event-handlers
  (t/testing "sanitize-svg removes event handler attributes"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" onload=\"alert('xss')\"><rect width=\"50\" height=\"50\" onmouseover=\"alert('xss')\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (clojure.string/includes? result "onload")))
      (t/is (not (clojure.string/includes? result "onmouseover")))
      (t/is (not (clojure.string/includes? result "alert")))
      (t/is (clojure.string/includes? result "<rect")))))

(t/deftest sanitize-svg-javascript-href
  (t/testing "sanitize-svg removes javascript: URLs from href attributes"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"100\" height=\"100\"><a xlink:href=\"javascript:alert('xss')\"><rect width=\"50\" height=\"50\"/></a></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (clojure.string/includes? result "javascript:")))
      (t/is (not (clojure.string/includes? result "alert")))
      (t/is (clojure.string/includes? result "<a")))))

(t/deftest sanitize-svg-foreign-object
  (t/testing "sanitize-svg removes foreignObject elements"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><foreignObject width=\"100\" height=\"100\"><body xmlns=\"http://www.w3.org/1999/xhtml\"><script>alert('xss')</script></body></foreignObject><rect width=\"50\" height=\"50\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (clojure.string/includes? result "foreignObject")))
      (t/is (not (clojure.string/includes? result "<script>")))
      (t/is (clojure.string/includes? result "<rect")))))

(t/deftest sanitize-svg-clean-content
  (t/testing "sanitize-svg preserves clean SVG content"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"50\" height=\"50\" fill=\"red\"/><circle cx=\"75\" cy=\"75\" r=\"20\" fill=\"blue\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (clojure.string/includes? result "<rect"))
      (t/is (clojure.string/includes? result "<circle"))
      (t/is (or (clojure.string/includes? result "fill=\"red\"")
                (clojure.string/includes? result "fill='red'")))
      (t/is (or (clojure.string/includes? result "fill=\"blue\"")
                (clojure.string/includes? result "fill='blue'"))))))

(t/deftest sanitize-svg-invalid-svg-rejected
  (t/testing "sanitize-svg rejects malformed SVG input"
    (let [svg "<svg><not-closed>"]
      (t/is (thrown-with-msg? Exception #"SVG parsing failed during sanitization"
                              (svg/sanitize-svg svg))))))

(t/deftest sanitize-svg-preserves-xlink
  (t/testing "sanitize-svg preserves legitimate xlink:href attributes"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"100\" height=\"100\"><use xlink:href=\"#icon\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (clojure.string/includes? result "xlink:href"))
      (t/is (clojure.string/includes? result "#icon")))))

(t/deftest sanitize-svg-javascript-href-whitespace
  (t/testing "sanitize-svg catches javascript: URLs with leading whitespace"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><a href=\" javascript:alert('xss')\"><rect width=\"50\" height=\"50\"/></a></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (clojure.string/includes? result "javascript:")))
      (t/is (not (clojure.string/includes? result "alert")))
      (t/is (clojure.string/includes? result "<a")))))

(t/deftest sanitize-svg-nested-script
  (t/testing "sanitize-svg removes script tags from nested elements"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><g><script>alert('xss')</script></g></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (clojure.string/includes? result "<script")))
      (t/is (not (clojure.string/includes? result "alert")))
      (t/is (clojure.string/includes? result "<g")))))

(t/deftest sanitize-svg-smil-bypass
  (t/testing "sanitize-svg removes SMIL animation elements that can set on* attrs"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"100\" height=\"100\" id=\"r\"/><set attributeName=\"onmouseover\" to=\"alert('xss')\" xlink:href=\"#r\" begin=\"0s\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (clojure.string/includes? result "<set")))
      (t/is (not (clojure.string/includes? result "onmouseover")))
      (t/is (clojure.string/includes? result "<rect")))))

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
            (t/is (= :validation (:type data)))
            (t/is (= :invalid-image (:code data)))))
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
