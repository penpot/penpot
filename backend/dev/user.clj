;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns user
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.fressian :as fres]
   [app.common.geom.matrix :as gmt]
   [app.common.logging :as l]
   [app.common.perf :as perf]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.schema.desc-js-like :as smdj]
   [app.common.schema.desc-native :as smdn]
   [app.common.schema.generators :as sg]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main :as main]
   [app.srepl.helpers :as srepl.helpers]
   [app.srepl.main :as srepl]
   [app.util.blob :as blob]
   [app.util.json :as json]
   [app.util.time :as dt]
   [clj-async-profiler.core :as prof]
   [clojure.contrib.humanize :as hum]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint print-table]]
   [clojure.repl :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.stacktrace :as trace]
   [clojure.test :as test]
   [clojure.test.check.generators :as tgen]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [criterium.core  :as crit]
   [cuerdas.core :as str]
   [datoteka.core]
   [integrant.core :as ig]
   [malli.core :as m]
   [malli.dev.pretty :as mdp]
   [malli.error :as me]
   [malli.generator :as mg]
   [malli.registry :as mr]
   [malli.transform :as mt]
   [malli.util :as mu]
   [promesa.exec :as px]))

(repl/disable-reload! (find-ns 'integrant.core))
(set! *warn-on-reflection* true)

(defonce system nil)

;; --- Benchmarking Tools

(defmacro run-quick-bench
  [& exprs]
  `(crit/with-progress-reporting (crit/quick-bench (do ~@exprs) :verbose)))

(defmacro run-quick-bench'
  [& exprs]
  `(crit/quick-bench (do ~@exprs)))

(defmacro run-bench
  [& exprs]
  `(crit/with-progress-reporting (crit/bench (do ~@exprs) :verbose)))

(defmacro run-bench'
  [& exprs]
  `(crit/bench (do ~@exprs)))

;; --- Development Stuff

(defn- run-tests
  ([] (run-tests #"^backend-tests.*-test$"))
  ([o]
   (repl/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))

(defn- start
  []
  (try
    (alter-var-root #'system (fn [sys]
                               (when sys (ig/halt! sys))
                               (-> main/system-config
                                   (cond-> (contains? cf/flags :backend-worker)
                                     (merge main/worker-config))
                                   (ig/prep)
                                   (ig/init))))
    :started
    (catch Throwable cause
      (ex/print-throwable cause))))

(defn- stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil))
  :stopped)

(defn restart
  []
  (stop)
  (repl/refresh :after 'user/start))

(defn restart-all
  []
  (stop)
  (repl/refresh-all :after 'user/start))

(defn compression-bench
  [data]
  (let [humanize (fn [v] (hum/filesize v :binary true :format " %.4f "))
        v1 (time (humanize (alength (blob/encode data {:version 1}))))
        v3 (time (humanize (alength (blob/encode data {:version 3}))))
        v4 (time (humanize (alength (blob/encode data {:version 4}))))
        v5 (time (humanize (alength (blob/encode data {:version 5}))))
        v6 (time (humanize (alength (blob/encode data {:version 6}))))
        ]
    (print-table
     [{
       :v1 v1
       :v3 v3
       :v4 v4
       :v5 v5
       :v6 v6
       }])))

(defonce debug-tap
  (do
    (add-tap #(locking debug-tap
                (prn "tap debug:" %)))
    1))


(sm/def! ::test
  [:map {:title "Foo"}
   [:x :int]
   [:y {:min 0} :double]
   [:bar
    [:map {:title "Bar"}
     [:z :string]
     [:v ::sm/uuid]]]
   [:items
    [:vector ::dt/instant]]])

(sm/def! ::test2
  [:multi {:title "Foo" :dispatch :type}
   [:x
    [:map {:title "FooX"}
     [:type [:= :x]]
     [:x :int]]]
   [:y
    [:map
     [:type [:= :x]]
     [:y [::sm/one-of #{:a :b :c}]]]]
   [:z
    [:map {:title "FooZ"}
     [:z
      [:multi {:title "Bar" :dispatch :type}
       [:a
        [:map
         [:type [:= :a]]
         [:a :int]]]
       [:b
        [:map
         [:type [:= :b]]
         [:b :int]]]]]]]])
