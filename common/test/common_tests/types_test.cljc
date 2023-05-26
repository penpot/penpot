;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types-test
  (:require
   [clojure.test :as t]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.transit :as transit]
   [app.common.types.shape :as cts]
   [app.common.types.page :as ctp]
   [app.common.types.file :as ctf]))

(t/deftest transit-encode-decode-with-shape
  (sg/check!
   (sg/for [fdata (sg/generator ::cts/shape)]
     (let [res (-> fdata transit/encode-str transit/decode-str)]
       (t/is (= res fdata))))
   {:num 18 :seed 1683548002439}))

(t/deftest types-shape-spec
  (sg/check!
   (sg/for [fdata (sg/generator ::cts/shape)]
     (binding [app.common.data.macros/*assert-context* true]
       (t/is (sm/valid? ::cts/shape fdata))))))

(t/deftest types-page-spec
  (-> (sg/for [fdata (sg/generator ::ctp/page)]
        (t/is (sm/validate ::ctp/page fdata)))
      (sg/check! {:num 30})))
