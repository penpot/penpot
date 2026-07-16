;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.thumbnails-test
  (:require
   [app.common.thumbnails :as thc]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- object-id
  [file-id page-id frame-id tag]
  (thc/fmt-object-id file-id page-id frame-id tag))

(t/deftest parse-object-id-strict-reads-the-package-shape
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        frame-id (uuid/next)]
    (t/is (= {:file-id file-id
              :page-id page-id
              :frame-id frame-id
              :tag "frame"}
             (thc/parse-object-id-strict
              (object-id file-id page-id frame-id "frame")))
          "the ids come back parsed and the tag as it is")
    (t/is (= "board"
             (:tag (thc/parse-object-id-strict
                    (object-id file-id page-id frame-id "board"))))
          "a tag other than the default is accepted")))

(t/deftest parse-object-id-strict-refuses-an-id-the-export-cannot-read
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        frame-id (uuid/next)
        valid    (object-id file-id page-id frame-id "frame")]
    (doseq [[label object-id]
            [["a short id" "test-key"]
             ["an empty id" ""]
             ["an extra segment" (str valid "/extra")]
             ["a segment that is not a uuid"
              (str file-id "/not-a-uuid/" frame-id "/frame")]
             ["a blank tag"
              (str file-id "/" page-id "/" frame-id "/ ")]
             ["a tag with a tab"
              (str file-id "/" page-id "/" frame-id "/bad\ttag")]
             ["a tag with a newline"
              (str file-id "/" page-id "/" frame-id "/bad\ntag")]
             ["a trailing separator" (str valid "/")]
             ["two trailing separators" (str valid "//")]]]
      (t/is (nil? (thc/parse-object-id-strict object-id)) label))))

(t/deftest parse-object-id-strict-answers-nil-without-a-string
  (t/is (nil? (thc/parse-object-id-strict nil)))
  (t/is (nil? (thc/parse-object-id-strict :frame)))
  (t/is (nil? (thc/parse-object-id-strict 42))))
