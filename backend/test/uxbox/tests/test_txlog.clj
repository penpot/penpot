(ns uxbox.tests.test-txlog
  "A txlog and services abstraction generic tests."
  (:require [clojure.test :as t]
            [promesa.core :as p]
            [uxbox.services.core :as usc]
            [uxbox.services :as usv]
            [uxbox.tests.helpers :as th]))

;; (t/use-fixtures :each th/database-reset)

;; (defmethod usc/novelty ::testype1
;;   [data]
;;   true)

;; (t/deftest txlog-spec1
;;   (let [data {:type ::testype1 :foo 1 :bar "baz"}
;;         response (usv/novelty data)]
;;     (t/is (p/promise? response))
;;     (t/is (= true @response))))
