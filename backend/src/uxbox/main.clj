;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main
  (:require [clojure.tools.namespace.repl :as repl]
            [clojure.walk :refer [macroexpand-all]]
            [clojure.pprint :refer [pprint]]
            [clojure.test :as test]
            [clojure.java.io :as io]
            [mount.core :as mount]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as b64]
            [buddy.core.nonce :as nonce]
            [uxbox.config :as cfg]
            [uxbox.migrations]
            [uxbox.db]
            [uxbox.frontend]
            [uxbox.scheduled-jobs])
  (:gen-class))

;; --- Development Stuff

(defn- start
  []
  (mount/start))

(defn- start-minimal
  []
  (-> (mount/only #{#'uxbox.config/config
                    #'uxbox.db/datasource
                    #'uxbox.migrations/migrations})
      (mount/start)))

(defn- stop
  []
  (mount/stop))

(defn- refresh
  []
  (stop)
  (repl/refresh))

(defn- refresh-all
  []
  (stop)
  (repl/refresh-all))

(defn- go
  "starts all states defined by defstate"
  []
  (start)
  :ready)

(defn- reset
  []
  (stop)
  (repl/refresh :after 'uxbox.main/start))

(defn make-secret
  []
  (-> (nonce/random-bytes 64)
      (b64/encode true)
      (codecs/bytes->str)))

;; --- Entry point (only for uberjar)

(defn test-vars
  [& vars]
  (repl/refresh)
  (test/test-vars
   (map (fn [sym]
          (require (symbol (namespace sym)))
          (resolve sym))
        vars)))

(defn test-ns
  [ns]
  (repl/refresh)
  (test/test-ns ns))

(defn test-all
  ([] (test/run-all-tests #"^uxbox.tests.*"))
  ([re] (test/run-all-tests re)))

;; --- Entry point (only for uberjar)

(defn -main
  [& args]
  (mount/start))
