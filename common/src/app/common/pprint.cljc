;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pprint
  (:refer-clojure :exclude [prn])
  (:require
   [cuerdas.core :as str]
   [fipp.edn :as fpp]))

(defn pprint-str
  [expr]
  (binding [*print-level* 8
            *print-length* 25]
    (with-out-str
      (fpp/pprint expr {:width 110}))))

(defn pprint
  ([expr]
   (println (pprint-str expr)))
  ([label expr]
   (println (str/concat "============ " label "============"))
   (pprint expr)))


