;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns user
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as test]
   [clojure.java.io :as io]
   [clojure.repl :refer :all]
   [criterium.core :refer [quick-bench bench with-progress-reporting]]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.migrations]
   [uxbox.util.storage :as st]
   [uxbox.util.time :as tm]
   [mount.core :as mount]))

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

(defn- start
  []
  (-> #_(mount/except #{#'uxbox.scheduled-jobs/scheduler})
      (mount/start)))

(defn- stop
  []
  (mount/stop))

(defn restart
  []
  (stop)
  (repl/refresh :after 'user/start))

(defn- run-tests
  ([] (run-tests #"^uxbox.tests.*"))
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
