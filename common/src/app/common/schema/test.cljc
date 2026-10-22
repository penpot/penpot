;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
  [{:keys [::params]}]
  (println "Shrunk generative case:")
  (binding [*print-length* nil *print-level* nil]
    (prn (merge (select-keys params [:seed])
                (select-keys (:shrunk params) [:smallest :total-nodes-visited
                                               :time-shrinking-ms])))))

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

    (println "---------------------------------------------------------")
    (println "Generative test:" (str "'" tsym "'")
             (str "(pass=FALSE, tests=" (:num-tests params)  ", seed=" (:seed params)  ")"))
    (pp/pprint (:fail params))
    (println "---------------------------------------------------------")

    (when (ex/exception? res)
      #?(:clj (ex/print-throwable res)
         :cljs (js/console.error res)))))

(defmacro for
  [bindings & body]
  `(tp/for-all ~bindings ~@body))

(defn- environment-number
  [name]
  (when-let [value #?(:clj (System/getenv name)
                      :cljs (some-> js/globalThis .-process .-env (aget name)))]
    (let [number #?(:clj (parse-long value) :cljs (js/Number value))]
      (when-not (and (integer? number) (pos? number))
        (throw (ex-info (str name " must be a positive integer")
                        {:variable name :value value})))
      number)))

(defn check!
  "Check a property with reproducible size and seed. PENPOT_GEN_NUM and
   PENPOT_GEN_SEED override the defaults; explicit options take precedence."
  [p & {:as options}]
  (let [num     (or (:num options) (environment-number "PENPOT_GEN_NUM") 20)
        seed    (or (:seed options) (environment-number "PENPOT_GEN_SEED"))
        options (cond-> (merge {:max-size 50 :reporter-fn default-reporter-fn}
                               (dissoc options :num))
                  seed (assoc :seed seed))
        result (tc/quick-check num p options)
        pass?        (:pass? result)
        total-tests  (:num-tests result)]

    (ct/is (= num total-tests))
    (ct/is (true? pass?))))
