;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.buffer-test
  (:require
   [app.common.buffer :as buf]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest allocate
  (let [b (buf/allocate 1)]
    (t/is (buf/buffer? b))))

(t/deftest rw-byte
  (let [b (buf/allocate 1)]
    (buf/write-byte b 0 123)
    (let [res (buf/read-byte b 0)]
      (t/is (= 123 res)))

    (buf/write-byte b 0 252)
    (let [res (buf/read-byte b 0)]
      (t/is (= -4 res)))))

(t/deftest rw-int
  (let [b (buf/allocate 4)]
    (buf/write-int b 0 123)
    (let [res (buf/read-int b 0)]
      (t/is (= 123 res)))))

(t/deftest rw-float
  (let [b (buf/allocate 4)]
    (buf/write-float b 0 123)
    (let [res (buf/read-float b 0)]
      (t/is (= 123.0 res)))))

(t/deftest rw-short
  (let [b (buf/allocate 2)]
    (buf/write-short b 0 123)
    (let [res (buf/read-short b 0)]
      (t/is (= 123 res)))))

(t/deftest rw-uuid
  (let [b  (buf/allocate 16)
        id (uuid/next)]
    (buf/write-uuid b 0 id)
    (let [res (buf/read-uuid b 0)]
      (t/is (= id res)))))
