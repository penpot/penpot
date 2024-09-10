;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.test
  (:refer-clojure :exclude [for])
  #?(:cljs (:require-macros [app.common.schema.test]))

  (:require
   [app.common.exceptions :as ex]
   [app.common.pprint :as pp]
   [clojure.test :as ct]
   [clojure.test.check :as tc]
   [clojure.test.check.properties :as tp]))

(defn- get-testing-var
  []
  (let [testing-vars #?(:clj ct/*testing-vars*
                        :cljs (:testing-vars ct/*current-env*))]
    (first testing-vars)))

(defn- get-testing-sym
  [var]
  (let [tmeta (meta var)]
    (:name tmeta)))

(defn default-reporter-fn
  "Default function passed as the :reporter-fn to clojure.test.check/quick-check.
  Delegates to clojure.test/report."
  [{:keys [type] :as args}]
  (case type
    :complete
    (ct/report {:type ::complete ::params args})

    :trial
    (ct/report {:type ::trial ::params args})

    :failure
    (ct/report {:type ::fail ::params args})

    :shrunk
    (ct/report {:type ::thrunk ::params args})

    nil))

(defmethod ct/report #?(:clj ::complete :cljs [:cljs.test/default ::complete])
  [{:keys [::params] :as m}]
  #?(:clj  (ct/inc-report-counter :pass)
     :cljs (ct/inc-report-counter! :pass))
  (let [tvar (get-testing-var)
        tsym (get-testing-sym tvar)
        time (:time-elapsed-ms params)]
    (println "Generative test:" (str "'" tsym "'")
             (str "(pass=TRUE, tests=" (:num-tests params)  ", seed=" (:seed params) ", elapsed=" time "ms)"))))

(defmethod ct/report #?(:clj ::thrunk :cljs [:cljs.test/default ::thrunk])
  [{:keys [::params] :as m}]
  (let [smallest (-> params :shrunk :smallest vec)]
    (println)
    (println "Condition failed with the following params:")
    (println)
    (pp/pprint smallest)))

(defmethod ct/report #?(:clj ::trial :cljs [:cljs.test/default ::trial])
  [_]
  #?(:clj  (ct/inc-report-counter :pass)
     :cljs (ct/inc-report-counter! :pass)))

(defmethod ct/report #?(:clj ::fail :cljs [:cljs.test/default ::fail])
  [{:keys [::params] :as m}]
  #?(:clj  (ct/inc-report-counter :fail)
     :cljs (ct/inc-report-counter! :fail))
  (let [tvar (get-testing-var)
        tsym (get-testing-sym tvar)
        res  (:result params)]
    (println)
    (println "Generative test:" (str "'" tsym "'")
             (str "(pass=FALSE, tests=" (:num-tests params)  ", seed=" (:seed params)  ")"))

    (when (ex/exception? res)
      #?(:clj (ex/print-throwable res)
         :cljs (js/console.error res)))))

(defmacro for
  [bindings & body]
  `(tp/for-all ~bindings ~@body))

(defn check!
  [p & {:keys [num] :or {num 20} :as options}]
  (let [result (tc/quick-check num p (assoc options :reporter-fn default-reporter-fn :max-size 50))
        pass?        (:pass? result)
        total-tests  (:num-tests result)]

    (ct/is (= num total-tests))
    (ct/is (true? pass?))))
