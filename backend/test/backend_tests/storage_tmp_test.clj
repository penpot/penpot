;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.storage-tmp-test
  (:require
   [app.storage.tmp :as tmp]
   [clojure.test :as t]
   [datoteka.fs :as fs])
  (:import
   java.nio.file.Files
   java.nio.file.Path))

(t/deftest temp-path-does-not-create-the-file
  (let [path (tmp/temp-path :prefix "penpot.test." :min-age "6h")]
    (t/testing "the reserved path does not exist yet"
      (t/is (not (fs/exists? path))))
    (t/testing "an external writer can create it with CREATE_NEW"
      (Files/createFile ^Path path (into-array java.nio.file.attribute.FileAttribute []))
      (t/is (fs/exists? path)))
    (fs/delete path)))

(t/deftest temp-path-is-unique
  (let [a (tmp/temp-path :prefix "penpot.test.")
        b (tmp/temp-path :prefix "penpot.test.")]
    (t/is (not= a b))
    (t/is (not (fs/exists? a)))
    (t/is (not (fs/exists? b)))))

(t/deftest tempfile-creates-the-file-so-create-new-fails
  (t/testing "documents why s3 downloads cannot use tempfile: the SDK opens with CREATE_NEW"
    (let [path (tmp/tempfile :prefix "penpot.test.")]
      (try
        (t/is (fs/exists? path))
        (t/is (thrown? java.nio.file.FileAlreadyExistsException
                       (Files/createFile ^Path path (into-array java.nio.file.attribute.FileAttribute []))))
        (finally
          (fs/delete path))))))
