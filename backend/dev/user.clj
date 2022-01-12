;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns user
  (:require
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.perf :as perf]
   [app.common.transit :as t]
   [app.config :as cfg]
   [app.main :as main]
   [app.util.blob :as blob]
   [app.util.fressian :as fres]
   [app.util.json :as json]
   [app.util.time :as dt]
   [clj-async-profiler.core :as prof]
   [clojure.contrib.humanize :as hum]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint print-table]]
   [clojure.repl :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as sgen]
   [clojure.test :as test]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [datoteka.core]
   [integrant.core :as ig]))

(repl/disable-reload! (find-ns 'integrant.core))
(set! *warn-on-reflection* true)

(defonce system nil)

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


;; (import
;;  'app.Experiments
;;  'app.Experiments$Matrix)

;; (defn bench-matrix-multiply
;;   []
;;   (let [ma1 (Experiments/create 1 2 3 4 5 6)
;;         ma2 (Experiments/create 6 5 4 3 2 1)]
;;     (perf/benchmark
;;      :f (fn []
;;           (dotimes [i 100]
;;             (when-not (Experiments/multiply ma1 ma2)
;;               (throw (ex-info "foobar" {}))))
;;           :result)
;;      :name "java matrix"))

;;   (let [ma1 (gmt/matrix 1 2 3 4 5 6)
;;         ma2 (gmt/matrix 6 5 4 3 2 1)]
;;     (perf/benchmark
;;      :f (fn []
;;           (dotimes [i 100]
;;             (when-not (gmt/multiply ma1 ma2)
;;               (throw (ex-info "foobar" {}))))
;;           :result)
;;      :name "orig matrix")))


;; (defn bench-matrix-multiply-bulk-5
;;   []
;;   (let [ma1 (Experiments/create 1 2 3 4 5 6)
;;         ma2 (Experiments/create 6 5 4 3 2 1)
;;         ma3 (Experiments/create 9 8 7 6 5 4)
;;         ma4 (Experiments/create 7 6 5 4 3 2)
;;         ma5 (Experiments/create 1 9 2 8 4 7)]

;;     (prn "result1" (seq (Experiments/multiplyBulk ma1 ma2 ma3 ma4 ma5)))
;;     (perf/benchmark
;;      :f (fn []
;;           (dotimes [i 100]
;;             (when-not (Experiments/multiplyBulk ma1 ma2 ma3 ma4 ma5)
;;               (throw (ex-info "foobar" {}))))
;;           :result)
;;      :name "java matrix"))

;;   (let [ma1 (gmt/matrix 1 2 3 4 5 6)
;;         ma2 (gmt/matrix 6 5 4 3 2 1)
;;         ma3 (gmt/matrix 9 8 7 6 5 4)
;;         ma4 (gmt/matrix 7 6 5 4 3 2)
;;         ma5 (gmt/matrix 1 9 2 8 4 7)]

;;     (prn "result2" (map second (gmt/multiply ma1 ma2 ma3 ma4 ma5)))
;;     (perf/benchmark
;;      :f (fn []
;;           (dotimes [i 100]
;;             (when-not (gmt/multiply ma1 ma2 ma3 ma4 ma5)
;;               (throw (ex-info "foobar" {}))))
;;           :result)
;;      :name "orig matrix")))

