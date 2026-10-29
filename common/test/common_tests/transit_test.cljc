;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.transit-test
  (:require
   [app.common.transit :as tr]
   [clojure.test :as t]))

;; A handler that always fails, to make a read throw after the reader has
;; already written to its read cache.
#?(:cljs
   (tr/add-handlers!
    {:id "test/throws"
     :rfn (fn [_] (throw (js/Error. "invalid representation")))}))

(t/deftest decode-str-round-trip
  (let [data {:a 1 :b [:c "d"] :e #{1 2}}]
    (t/is (= data (-> data tr/encode-str tr/decode-str)))
    (t/is (= data (-> data (tr/encode-str {:type :json-verbose}) tr/decode-str)))))

(t/deftest decode-str-uses-handlers-added-after-the-first-read
  (t/is (= [:x] (tr/decode-str "[\"~:x\"]")))
  (tr/add-handlers! {:id "test/late" :rfn (fn [v] (str "late:" v))})
  (t/is (= "late:x" (tr/decode-str "[\"~#test/late\",\"x\"]"))))

;; In ClojureScript `decode-str` reuses one reader, and a read that throws
;; leaves that reader's read cache dirty. The next message then resolves
;; its cache codes ("^0", "^1") to entries of the failed one.
#?(:cljs
   (t/deftest decode-str-after-a-failed-read
     (let [good     "[\"~:aaaa\",\"~:bbbb\",\"^0\",\"^1\"]"
           bad      "[\"~:cccc\",[\"~#test/throws\",1]]"
           expected [:aaaa :bbbb :aaaa :bbbb]]
       (t/is (= expected (tr/decode-str good)))
       (t/is (thrown? js/Error (tr/decode-str bad)))
       (t/is (= expected (tr/decode-str good)))
       (t/is (= expected (tr/decode-str good))))))
