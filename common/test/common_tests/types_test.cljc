;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as props]
   [app.common.spec :as us]
   [app.common.transit :as transit]
   [app.common.types.shape :as cts]
   [app.common.types.page :as ctp]
   [app.common.types.file :as ctf]))

(defspec transit-encode-decode-with-shape 10
  (props/for-all
   [fdata (s/gen ::cts/shape)]
   (let [res (-> fdata transit/encode-str transit/decode-str)]
     (t/is (= res fdata)))))

(defspec types-shape-spec 5
  (props/for-all
   [fdata (s/gen ::cts/shape)]
   (t/is (us/valid? ::cts/shape fdata))))

(defspec types-page-spec 5
  (props/for-all
   [fdata (s/gen ::ctp/page)]
   (t/is (us/valid? ::ctp/page fdata))))

(defspec types-file-colors-spec 10
  (props/for-all
   [fdata (s/gen ::ctf/colors)]
   (t/is (us/valid? ::ctf/colors fdata))))

(defspec types-file-recent-colors-spec 10
  (props/for-all
   [fdata (s/gen ::ctf/recent-colors)]
   (t/is (us/valid? ::ctf/recent-colors fdata))))

(defspec types-file-typographies-spec 10
  (props/for-all
   [fdata (s/gen ::ctf/typographies)]
   (t/is (us/valid? ::ctf/typographies fdata))))

(defspec types-file-media-spec 10
  (props/for-all
   [fdata (s/gen ::ctf/media)]
   (t/is (us/valid? ::ctf/media fdata))))

(defspec types-file-components-spec 1
  (props/for-all
   [fdata (s/gen ::ctf/components)]
   (t/is (us/valid? ::ctf/components fdata))))
