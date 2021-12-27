;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns user
  (:require
   [datoteka.core]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.main :as main]
   [app.util.blob :as blob]
   [app.util.json :as json]
   [app.util.fressian :as fres]
   [app.util.time :as dt]
   [app.common.transit :as t]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint print-table]]
   [clojure.contrib.humanize :as hum]
   [clojure.repl :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as sgen]
   [clojure.test :as test]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [clj-async-profiler.core :as prof]
   [criterium.core :refer [quick-bench bench with-progress-reporting]]
   [integrant.core :as ig]))

(repl/disable-reload! (find-ns 'integrant.core))
(set! *warn-on-reflection* true)

(defonce system nil)

;; --- Benchmarking Tools

(defmacro run-quick-bench
  [& exprs]
  `(with-progress-reporting (quick-bench (do ~@exprs) :verbose)))

(defmacro run-quick-bench'
  [& exprs]
  `(quick-bench (do ~@exprs)))

(defmacro run-bench
  [& exprs]
  `(with-progress-reporting (bench (do ~@exprs) :verbose)))

(defmacro run-bench'
  [& exprs]
  `(bench (do ~@exprs)))

;; --- Development Stuff

(defn- run-tests
  ([] (run-tests #"^app.*-test$"))
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
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             (-> main/system-config
                                 (ig/prep)
                                 (ig/init))))
  :started)

(defn- stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil))
  :stoped)

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
  (let [humanize (fn [v] (hum/filesize v :binary true :format " %.4f "))]
    (print-table
     [{:v1 (humanize (alength (blob/encode data {:version 1})))
       :v2 (humanize (alength (blob/encode data {:version 2})))
       :v3 (humanize (alength (blob/encode data {:version 3})))
       :v4 (humanize (alength (blob/encode data {:version 4})))
       }])))

(defonce debug-tap
  (do
    (add-tap #(locking debug-tap
                (prn "tap debug:" %)))
    1))
