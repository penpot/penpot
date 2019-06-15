(ns uxbox.tests.test-svgparse
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [uxbox.api :as uapi]
            [uxbox.services :as usv]
            [uxbox.services.svgparse :as svg]
            [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)

(t/deftest parse-svg-test
  (t/testing "parsing valid svg 1"
    (let [image (slurp (io/resource "uxbox/tests/_files/sample1.svg"))
          result (svg/parse-string image)]
      (t/is (contains? result :width))
      (t/is (contains? result :height))
      (t/is (contains? result :view-box))
      (t/is (contains? result :name))
      (t/is (contains? result :content))
      (t/is (= 500.0 (:width result)))
      (t/is (= 500.0 (:height result)))
      (t/is (= [0.0 0.0 500.00001 500.00001] (:view-box result)))
      (t/is (= "lock.svg" (:name result)))))

  (t/testing "parsing valid svg 2"
    (let [image (slurp (io/resource "uxbox/tests/_files/sample2.svg"))
          result (svg/parse-string image)]
      (t/is (contains? result :width))
      (t/is (contains? result :height))
      (t/is (contains? result :view-box))
      (t/is (contains? result :name))
      (t/is (contains? result :content))
      (t/is (= 500.0 (:width result)))
      (t/is (= 500.0 (:height result)))
      (t/is (= [0.0 0.0 500.0 500.00001] (:view-box result)))
      (t/is (= "play.svg" (:name result)))))

  (t/testing "parsing invalid data 1"
    (let [image (slurp (io/resource "uxbox/tests/_files/sample.jpg"))
          [e result] (th/try-on (svg/parse-string image))]
      (t/is (th/exception? e))
      (t/is (th/ex-info? e))
      (t/is (th/ex-with-code? e :uxbox.services.svgparse/invalid-input))))

  (t/testing "parsing invalid data 2"
    (let [[e result] (th/try-on (svg/parse-string ""))]
      (t/is (th/exception? e))
      (t/is (th/ex-info? e))
      (t/is (th/ex-with-code? e :uxbox.services.svgparse/invalid-input))))

  (t/testing "parsing invalid data 3"
    (let [[e result] (th/try-on (svg/parse-string "<svg></svg>"))]
      (t/is (th/exception? e))
      (t/is (th/ex-info? e))
      (t/is (th/ex-with-code? e :uxbox.services.svgparse/invalid-result))))

  ;; (t/testing "valid http request"
  ;;   (with-open [conn (db/connection)]
  ;;     (let [image (slurp (io/resource "uxbox/tests/_files/sample2.svg"))
  ;;           path "/api/svg/parse"
  ;;           user (th/create-user conn 1)]
  ;;       (th/with-server {:handler uapi/app}
  ;;         (let [rsp (th/request {:method :post
  ;;                                :path path
  ;;                                :body image
  ;;                                :raw? true})]
  ;;           (t/is (= 200 (:status rsp)))
  ;;           (prn "RESPONSE" rsp)
  ;;           ;; (t/is (contains? (:body rsp) :width))
  ;;           ;; (t/is (contains? (:body rsp) :height))
  ;;           ;; (t/is (contains? (:body rsp) :view-box))
  ;;           ;; (t/is (contains? (:body rsp) :name))
  ;;           ;; (t/is (contains? (:body rsp) :content))
  ;;           ;; (t/is (= 500.0 (:width (:body rsp))))
  ;;           #_(t/is (= 500.0 (:height (:body rsp))))
  ;;           #_(t/is (= [0.0 0.0 500.0 500.00001] (:view-box (:body rsp))))
  ;;           #_(t/is (= "play.svg" (:name (:body rsp))))))))

  ;;   (t/testing "invalid http request"
  ;;     (let [path "/api/svg/parse"
  ;;           image "<svg></svg>"]
  ;;       (with-server {:handler (uft/routes)}
  ;;         (let [rsp (th/request {:method :post
  ;;                                :path path
  ;;                                :body image
  ;;                                :raw? true})]
  ;;           (t/is (= 400 (:status rsp)))
  ;;           (t/is (= :validation (get-in rsp [:body :type])))
  ;;           (t/is (= ::svg/invalid-result (get-in rsp [:body :code])))))))
  )
