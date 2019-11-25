(ns uxbox.tests.test-util-svg
  (:require
   [clojure.test :as t]
   [clojure.java.io :as io]
   [uxbox.http :as http]
   [uxbox.util.svg :as svg]
   [uxbox.tests.helpers :as th]))

(t/deftest parse-svg-1
  (let [result (-> (io/resource "uxbox/tests/_files/sample1.svg")
                   (svg/parse))]
    (t/is (contains? result :width))
    (t/is (contains? result :height))
    (t/is (contains? result :view-box))
    (t/is (contains? result :name))
    (t/is (contains? result :content))
    (t/is (= 500.0 (:width result)))
    (t/is (= 500.0 (:height result)))
    (t/is (= [0.0 0.0 500.00001 500.00001] (:view-box result)))
    (t/is (= "lock.svg" (:name result)))))

(t/deftest parse-svg-2
  (let [result (-> (io/resource "uxbox/tests/_files/sample2.svg")
                   (svg/parse))]
      (t/is (contains? result :width))
      (t/is (contains? result :height))
      (t/is (contains? result :view-box))
      (t/is (contains? result :name))
      (t/is (contains? result :content))
      (t/is (= 500.0 (:width result)))
      (t/is (= 500.0 (:height result)))
      (t/is (= [0.0 0.0 500.0 500.00001] (:view-box result)))
      (t/is (= "play.svg" (:name result)))))

(t/deftest parse-invalid-svg-1
  (let [image (io/resource "uxbox/tests/_files/sample.jpg")
        result (th/try! (svg/parse image))]
    (t/is (map? (:error result)))
    (t/is (= (get-in result [:error :code])
             ::svg/invalid-input))))

(t/deftest parse-invalid-svg-2
  (let [result (th/try! (svg/parse-string ""))]
    (t/is (map? (:error result)))
    (t/is (= (get-in result [:error :code])
             ::svg/invalid-input))))

(t/deftest parse-invalid-svg-3
  (let [result (th/try! (svg/parse-string "<svg></svg>"))]
    (t/is (map? (:error result)))
    (t/is (= (get-in result [:error :code])
             ::svg/invalid-result))))
