;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.shape-decode-encode-test
  (:require
   [app.common.json :as json]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.types.color :refer [schema:color schema:gradient]]
   [app.common.types.path :as path]
   [app.common.types.plugins :refer [schema:plugin-data]]
   [app.common.types.shape :as tsh]
   [app.common.types.shape.interactions :refer [schema:animation schema:interaction]]
   [app.common.types.shape.shadow :refer [schema:shadow]]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn json-roundtrip
  [data]
  (-> data
      (json/encode :key-fn json/write-camel-key)
      (json/decode :key-fn json/read-kebab-key)))

(t/deftest map-of-with-strings
  (let [schema [:map [:data [:map-of :string :int]]]
        encode (sm/encoder schema (sm/json-transformer))
        decode (sm/decoder schema (sm/json-transformer))

        data1  {:data {"foo/bar" 1
                       "foo-baz" 2}}

        data2  (encode data1)
        data3  (json-roundtrip data2)
        data4  (decode data3)]

    ;; (pp/pprint data1)
    ;; (pp/pprint data2)
    ;; (pp/pprint data3)
    ;; (pp/pprint data4)

    (t/is (= data1 data2))
    (t/is (= data1 data4))
    (t/is (not= data1 data3))))

(t/deftest gradient-json-roundtrip
  (let [encode (sm/encoder schema:gradient (sm/json-transformer))
        decode (sm/decoder schema:gradient (sm/json-transformer))]
    (smt/check!
     (smt/for [gradient (sg/generator schema:gradient)]
       (let [gradient-1 (encode gradient)
             gradient-2 (json-roundtrip gradient-1)
             gradient-3 (decode gradient-2)]
         ;; (app.common.pprint/pprint gradient)
         ;; (app.common.pprint/pprint gradient-3)
         (= gradient gradient-3)))
     {:num 500})))

(t/deftest color-json-roundtrip
  (let [encode (sm/encoder schema:color (sm/json-transformer))
        decode (sm/decoder schema:color (sm/json-transformer))]
    (smt/check!
     (smt/for [color (sg/generator schema:color)]
       (let [color-1 (encode color)
             color-2 (json-roundtrip color-1)
             color-3 (decode color-2)]
         ;; (app.common.pprint/pprint color)
         ;; (app.common.pprint/pprint color-3)
         (= color color-3)))
     {:num 500})))

(t/deftest shape-shadow-json-roundtrip
  (let [encode (sm/encoder schema:shadow (sm/json-transformer))
        decode (sm/decoder schema:shadow (sm/json-transformer))]
    (smt/check!
     (smt/for [shadow (sg/generator schema:shadow)]
       (let [shadow-1 (encode shadow)
             shadow-2 (json-roundtrip shadow-1)
             shadow-3 (decode shadow-2)]
         ;; (app.common.pprint/pprint shadow)
         ;; (app.common.pprint/pprint shadow-3)
         (= shadow shadow-3)))
     {:num 500})))

(t/deftest shape-animation-json-roundtrip
  (let [encode (sm/encoder schema:animation (sm/json-transformer))
        decode (sm/decoder schema:animation (sm/json-transformer))]
    (smt/check!
     (smt/for [animation (sg/generator schema:animation)]
       (let [animation-1 (encode animation)
             animation-2 (json-roundtrip animation-1)
             animation-3 (decode animation-2)]
         ;; (app.common.pprint/pprint animation)
         ;; (app.common.pprint/pprint animation-3)
         (= animation animation-3)))
     {:num 500})))

(t/deftest shape-interaction-json-roundtrip
  (let [encode (sm/encoder schema:interaction (sm/json-transformer))
        decode (sm/decoder schema:interaction (sm/json-transformer))]
    (smt/check!
     (smt/for [interaction (sg/generator schema:interaction)]
       (let [interaction-1 (encode interaction)
             interaction-2 (json-roundtrip interaction-1)
             interaction-3 (decode interaction-2)]
         ;; (app.common.pprint/pprint interaction)
         ;; (app.common.pprint/pprint interaction-3)
         (= interaction interaction-3)))
     {:num 500})))

(t/deftest shape-path-content-json-roundtrip
  (let [encode (sm/encoder path/schema:content (sm/json-transformer))
        decode (sm/decoder path/schema:content (sm/json-transformer))]
    (smt/check!
     (smt/for [path-content (sg/generator path/schema:content)]
       (let [path-content-1 (encode path-content)
             path-content-2 (json-roundtrip path-content-1)
             path-content-3 (decode path-content-2)]
         (= path-content path-content-3)))
     {:num 500})))

(t/deftest plugin-data-json-roundtrip
  (let [encode (sm/encoder schema:plugin-data (sm/json-transformer))
        decode (sm/decoder schema:plugin-data (sm/json-transformer))]
    (smt/check!
     (smt/for [data (sg/generator schema:plugin-data)]
       (let [data-1 (encode data)
             data-2 (json-roundtrip data-1)
             data-3 (decode data-2)]
         (= data data-3)))
     {:num 500})))

(t/deftest shape-json-roundtrip
  (let [encode (sm/encoder ::tsh/shape (sm/json-transformer))
        decode (sm/decoder ::tsh/shape (sm/json-transformer))]
    (smt/check!
     (smt/for [shape (sg/generator ::tsh/shape)]
       (let [shape-1 (encode shape)
             shape-2 (json-roundtrip shape-1)
             shape-3 (decode shape-2)]
         ;; (app.common.pprint/pprint shape)
         ;; (app.common.pprint/pprint shape-3)
         (= shape shape-3)))
     {:num 200})))
