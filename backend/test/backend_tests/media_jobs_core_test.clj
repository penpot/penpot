;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.media-jobs-core-test
  (:require
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.media.jobs :as mj]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(defn- check
  [schema value]
  (true? ((sm/validator schema) value)))

(defn- storage
  []
  (sto/resolve th/*system*))

(t/deftest params-and-result-schemas-are-defined-once
  (let [input  {:id (uuid/next) :mtype "image/png" :size 10}
        result {:id (uuid/next) :uri "https://example.com/assets/by-id/x"
                :mtype "image/png" :size 10}]

    (t/testing "media-info"
      (t/is (check mj/schema:media-info {:input input}))
      (t/is (check mj/schema:media-info-result
                   {:width 10 :height 10 :mtype "image/png" :size 10})))

    (t/testing "media-thumbnail"
      (t/is (check mj/schema:media-thumbnail
                   {:input input
                    :media-id (uuid/next)
                    :format :jpeg
                    :quality 85
                    :width 100
                    :height 100}))
      (t/is (check mj/schema:media-thumbnail-result {:thumbnail result})))

    (t/testing "profile-thumbnail"
      (t/is (check mj/schema:profile-thumbnail {:input input}))
      (t/is (check mj/schema:profile-thumbnail-result {:photo result})))

    (t/testing "font-variants"
      (t/is (check mj/schema:font-variants
                   {:input input
                    :variant-id (uuid/next)
                    :source-mtype "font/ttf"
                    :targets ["font/otf" "font/woff"]}))
      (t/is (check mj/schema:font-variants-result {:variants [result]})))

    (t/testing "a local path never passes as an input"
      (t/is (false? (check mj/schema:media-info
                           {:input (assoc input :path "/tmp/upload.png")}))))

    (t/testing "the worker only receives the known output formats"
      (t/is (false? (check mj/schema:media-thumbnail
                           {:input input
                            :media-id (uuid/next)
                            :format :gif
                            :quality 85
                            :width 100
                            :height 100}))))))

(t/deftest materialize-input-uploads-to-tempfile
  (let [path (th/tempfile "backend_tests/test_files/sample.png")
        ref  (mj/materialize-input th/*system* {:path path :mtype "image/png"})
        obj  (sto/get-object (storage) (:id ref))]

    (t/is (uuid? (:id ref)))
    (t/is (= "image/png" (:mtype ref)))
    (t/is (= (fs/size path) (:size ref)))

    (t/is (some? obj))
    (t/is (= "tempfile" (-> obj meta :bucket)))
    (t/is (nil? (-> obj meta :profile-id))
          "a temporary input has no owner; the shared key reads it")))

(t/deftest upload-result-mirrors-the-worker-shape
  (let [path   (th/tempfile "backend_tests/test_files/sample.png")
        result (mj/upload-result th/*system* path {:mtype "image/png"})
        obj    (sto/get-object (storage) (:id result))]

    (t/is (uuid? (:id result)))
    (t/is (= "image/png" (:mtype result)))
    (t/is (= (fs/size path) (:size result)))
    (t/is (str/includes? (:uri result) (str (:id result))))
    (t/is (= "tempfile" (-> obj meta :bucket)))))

(t/deftest store-result-moves-the-artifact-and-releases-the-temporary
  (let [path     (th/tempfile "backend_tests/test_files/sample.png")
        result   (mj/upload-result th/*system* path {:mtype "image/png"})
        object   (mj/store-result th/*system* (:id result) "file-media-object"
                                  {:content-type "image/png"})]

    (t/is (some? object))
    (t/is (not= (:id result) (:id object)))
    (t/is (= "file-media-object" (-> object meta :bucket)))
    (t/is (= "image/png" (-> object meta :content-type)))

    (t/testing "the temporary object is marked as deleted"
      (t/is (nil? (sto/get-object (storage) (:id result)))))))

(t/deftest store-result-raises-when-the-artifact-is-gone
  (let [cause (try
                (mj/store-result th/*system* (uuid/next) "file-media-object"
                                 {:content-type "image/png"})
                nil
                (catch Throwable cause cause))]
    (t/is (= :media-result-expired (:type (ex-data cause))))
    (t/is (= :media-result-expired (:code (ex-data cause))))))

(t/deftest normalize-thumbnail-format-only-produces-known-formats
  (t/testing "the natural format wins when the worker supports it"
    (t/is (= :png (mj/normalize-thumbnail-format "image/png")))
    (t/is (= :jpeg (mj/normalize-thumbnail-format "image/jpeg")))
    (t/is (= :webp (mj/normalize-thumbnail-format "image/webp"))))

  (t/testing "an explicit format wins"
    (t/is (= :webp (mj/normalize-thumbnail-format "image/png" :webp))))

  (t/testing "a gif or an svg falls back to jpeg"
    (t/is (= :jpeg (mj/normalize-thumbnail-format "image/gif")))
    (t/is (= :jpeg (mj/normalize-thumbnail-format "image/gif" :gif)))
    (t/is (= :jpeg (mj/normalize-thumbnail-format "image/svg+xml")))))

(t/deftest queue-pair-follows-the-remote-flag
  (with-redefs [cf/flags (disj cf/flags :remote-media-processing)]
    (t/is (= {:fast :media-local-fast :slow :media-local}
             (mj/queue-pair))))

  (with-redefs [cf/flags (conj cf/flags :remote-media-processing)]
    (t/is (= {:fast :media-fast :slow :media}
             (mj/queue-pair)))))

(t/deftest labels-name-the-resource-of-the-job
  (t/is (= "media:42" (mj/object-label 42)))
  (t/is (= "font:42" (mj/font-label 42)))
  (t/is (= "profile:42" (mj/profile-label 42))))
