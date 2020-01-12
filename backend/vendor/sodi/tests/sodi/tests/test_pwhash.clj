(ns sodi.tests.test-pwhash
  (:require
   [clojure.test :as t]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as props]
   [sodi.pwhash :as pwh]))

(defspec derive-verify-roundtrip 1000
  (props/for-all
   [password gen/string]
   (let [pwhash (pwh/derive password {:cpucost 10})
         result (pwh/verify password pwhash)]
     (t/is (true? (:valid result))))))

(defspec derive-verify-roundtrip-invalid 1000
  (props/for-all
   [pw1 gen/string
    pw2 (gen/such-that #(not= % pw1) gen/string)]
   (let [pwhash (pwh/derive pw1 {:cpucost 10})
         result (pwh/verify pw2 pwhash)]
     (t/is (false? (:valid result))))))
