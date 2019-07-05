;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns user
  (:require [clojure.tools.namespace.repl :as repl]
            [clojure.walk :refer [macroexpand-all]]
            [clojure.pprint :refer [pprint]]
            [clojure.test :as test]
            [clojure.java.io :as io]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as b64]
            [buddy.core.nonce :as nonce]
            [mount.core :as mount]
            [uxbox.main])
  (:gen-class))

;; --- Development Stuff

(defn- make-secret
  []
  (-> (nonce/random-bytes 64)
      (b64/encode true)
      (codecs/bytes->str)))

(defn- start
  []
  (-> (mount/except #{#'uxbox.scheduled-jobs/scheduler})
      (mount/start)))

(defn- stop
  []
  (mount/stop))

(defn- start-minimal
  []
  (-> (mount/only #{#'uxbox.config/config
                    #'uxbox.db/datasource
                    #'uxbox.migrations/migrations})
      (mount/start)))

(defn- run-test
  ([] (run-test #"^uxbox.tests.*"))
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
