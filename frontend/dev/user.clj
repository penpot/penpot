;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns user
  (:require
   [app.common.data :as d]
   [app.common.pprint :as pp]
   [clojure.java.io :as io]
   [clojure.tools.namespace.repl :as repl]
   [clojure.pprint :refer [pprint print-table]]
   [clojure.repl :refer :all]
   [clojure.walk :refer [macroexpand-all]]
   [criterium.core  :as crit]))

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
