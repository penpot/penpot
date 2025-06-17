;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.uuid-test
  (:require
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn create-array
  [data]
  #?(:clj (byte-array data)
     :cljs (.from js/Int8Array (into-array data))))

(t/deftest bytes-roundtrip
  (let [uuid           (uuid/uuid "0227df82-63d7-8016-8005-48d9c0f33011")
        result-bytes   (uuid/get-bytes uuid)
        expected-bytes [2 39 -33 -126 99 -41 -128 22 -128 5 72 -39 -64 -13 48 17]]

    (t/testing "get-bytes"
      (let [data (uuid/get-bytes uuid)]
        (t/is (= (nth expected-bytes 0)  (aget data 0)))
        (t/is (= (nth expected-bytes 1)  (aget data 1)))
        (t/is (= (nth expected-bytes 2)  (aget data 2)))
        (t/is (= (nth expected-bytes 3)  (aget data 3)))
        (t/is (= (nth expected-bytes 4)  (aget data 4)))
        (t/is (= (nth expected-bytes 5)  (aget data 5)))
        (t/is (= (nth expected-bytes 6)  (aget data 6)))
        (t/is (= (nth expected-bytes 7)  (aget data 7)))
        (t/is (= (nth expected-bytes 8)  (aget data 8)))
        (t/is (= (nth expected-bytes 9)  (aget data 9)))
        (t/is (= (nth expected-bytes 10) (aget data 10)))
        (t/is (= (nth expected-bytes 11) (aget data 11)))
        (t/is (= (nth expected-bytes 12) (aget data 12)))
        (t/is (= (nth expected-bytes 13) (aget data 13)))
        (t/is (= (nth expected-bytes 14) (aget data 14)))
        (t/is (= (nth expected-bytes 15) (aget data 15)))))

    (t/testing "from-bytes"
      (let [data   (create-array expected-bytes)
            result (uuid/from-bytes data)]
        (t/is (= result uuid))))))


(t/deftest bytes-roundtrip-2
  (let [uuid           (uuid/uuid "a1a2a3a4-b1b2-c1c2-d1d2-d3d4d5d6d7d8")
        result-bytes   (uuid/get-bytes uuid)
        expected-hi    #?(:clj -6799692559624781374
                          :cljs (js/BigInt "-6799692559624781374"))
        expected-lo    #?(:clj -3327364263599220776
                          :cljs (js/BigInt "-3327364263599220776"))

        expected-bytes [-95, -94, -93, -92, -79, -78, -63, -62, -47, -46, -45, -44, -43, -42, -41, -40]]

    (t/testing "get-bytes"
      (let [data (uuid/get-bytes uuid)]
        (t/is (= (nth expected-bytes 0)  (aget data 0)))
        (t/is (= (nth expected-bytes 1)  (aget data 1)))
        (t/is (= (nth expected-bytes 2)  (aget data 2)))
        (t/is (= (nth expected-bytes 3)  (aget data 3)))
        (t/is (= (nth expected-bytes 4)  (aget data 4)))
        (t/is (= (nth expected-bytes 5)  (aget data 5)))
        (t/is (= (nth expected-bytes 6)  (aget data 6)))
        (t/is (= (nth expected-bytes 7)  (aget data 7)))
        (t/is (= (nth expected-bytes 8)  (aget data 8)))
        (t/is (= (nth expected-bytes 9)  (aget data 9)))
        (t/is (= (nth expected-bytes 10) (aget data 10)))
        (t/is (= (nth expected-bytes 11) (aget data 11)))
        (t/is (= (nth expected-bytes 12) (aget data 12)))
        (t/is (= (nth expected-bytes 13) (aget data 13)))
        (t/is (= (nth expected-bytes 14) (aget data 14)))
        (t/is (= (nth expected-bytes 15) (aget data 15)))))

    (t/testing "from-bytes"
      (let [data   (create-array expected-bytes)
            result (uuid/from-bytes data)]
        (t/is (= result uuid))))

    (t/testing "hi-low"
      (let [hi (uuid/get-word-high uuid)
            lo (uuid/get-word-low uuid)]

        (t/is (= hi expected-hi))
        (t/is (= lo expected-lo))))

    #?(:cljs
       (t/testing "unsigned-parts"
         (let [parts    (uuid/get-unsigned-parts uuid)
               expected [2711790500, 2981282242, 3520254932, 3587626968]]

           (t/is (instance? js/Uint32Array parts))
           (t/is (= (nth expected 0) (aget parts 0)))
           (t/is (= (nth expected 1) (aget parts 1)))
           (t/is (= (nth expected 2) (aget parts 2)))
           (t/is (= (nth expected 3) (aget parts 3))))))))
