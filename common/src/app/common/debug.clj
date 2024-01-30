;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.debug
  (:require
   [app.common.logging :as l]
   [app.common.pprint :as pp]))

(defn pprint
  [expr]
  (l/raw! :debug
          (binding [*print-level* pp/default-level
                    *print-length* pp/default-length]
            (with-out-str
              (println "tap dbg:")
              (pp/pprint expr {:max-width pp/default-width})))))


(def store (atom {}))

(defn get-stored
  []
  (deref store))

(defn tap-handler
  [v]
  (if (and (vector? v)
           (keyword (first v)))
    (let [[command obj] v]
      (case command
        (:print :prn :pprint) (pprint obj)
        :store  (reset! store obj)))
    (pprint v)))
