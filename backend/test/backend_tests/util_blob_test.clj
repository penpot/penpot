;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.util-blob-test
  (:require
   [app.util.blob :as blob]
   [clojure.string :as str]
   [clojure.test :as t]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; encode-str / decode-str round-trip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest encode-str-roundtrip-empty-map
  (let [data {}]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

(t/deftest encode-str-roundtrip-empty-vector
  (let [data []]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

(t/deftest encode-str-roundtrip-nil
  (let [data nil]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

(t/deftest encode-str-roundtrip-simple-map
  (let [data {:name "penpot" :version 42}]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

(t/deftest encode-str-roundtrip-nested-structure
  (let [data {:users [{:name "Alice" :tags #{"admin" "active"}}
                      {:name "Bob" :tags #{"user"}}]
              :config {:debug false :timeout 3000}}]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

(t/deftest encode-str-roundtrip-vector-of-maps
  (let [data [{:name "navigate" :type "action" :source "telemetry"}
              {:name "create-file" :type "action" :source "telemetry"}]]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

(t/deftest encode-str-roundtrip-keywords-and-strings
  (let [data {:keyword/value :foo
              :string/value "hello world"
              :boolean/value true
              :nil/value nil}]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

(t/deftest encode-str-roundtrip-numeric-types
  (let [data {:int 42
              :neg -7
              :zero 0
              :big 9999999999}]
    (t/is (= data (blob/decode-str (blob/encode-str data))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; URL-safe encoding properties
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest encode-str-url-safe-no-unsafe-chars
  ;; URL-safe base64 must not contain +, /, or padding =
  (let [data {:a (apply str (repeat 100 "x"))
              :b (range 200)
              :c {"key" "value with special chars: @#$%^&*()"}}
        encoded (blob/encode-str data)]
    (t/is (not (str/includes? encoded "+")))
    (t/is (not (str/includes? encoded "/")))
    (t/is (not (str/includes? encoded "=")))))

(t/deftest encode-str-url-safe-roundtrip-after-encoding
  ;; Ensure the URL-safe encoding still round-trips correctly
  (let [data {:payload (vec (range 500))
              :nested {:a {:b {:c "deep"}}}}
        encoded (blob/encode-str data)
        decoded (blob/decode-str encoded)]
    (t/is (= data decoded))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; version-specific encoding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest encode-str-with-version-4
  (let [data {:events [{:name "click"} {:name "scroll"}]}
        encoded (blob/encode-str data {:version 4})
        decoded (blob/decode-str encoded)]
    (t/is (= data decoded))))

(t/deftest encode-str-with-version-5
  (let [data {:events [{:name "click"} {:name "scroll"}]}
        encoded (blob/encode-str data {:version 5})
        decoded (blob/decode-str encoded)]
    (t/is (= data decoded))))

(t/deftest encode-str-with-version-1
  (let [data {:simple "data"}
        encoded (blob/encode-str data {:version 1})
        decoded (blob/decode-str encoded)]
    (t/is (= data decoded))))

(t/deftest encode-str-with-version-3
  (let [data {:simple "data"}
        encoded (blob/encode-str data {:version 3})
        decoded (blob/decode-str encoded)]
    (t/is (= data decoded))))
