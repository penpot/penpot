;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.util.log4j
  (:require
   [clojure.pprint :refer [pprint]])
  (:import
   org.apache.logging.log4j.ThreadContext))

(defn update-thread-context!
  [data]
  (run! (fn [[key val]]
          (ThreadContext/put
           (name key)
           (cond
             (coll? val)
             (binding [clojure.pprint/*print-right-margin* 120]
               (with-out-str (pprint val)))
             (instance? clojure.lang.Named val) (name val)
             :else (str val))))
        data))
