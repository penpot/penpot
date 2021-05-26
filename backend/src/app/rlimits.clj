;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rlimits
  "Resource usage limits (in other words: semaphores)."
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig])
  (:import
   java.util.concurrent.Semaphore))

(s/def ::rlimit  #(instance? Semaphore %))
(s/def ::rlimits (s/map-of ::us/keyword ::rlimit))

(derive ::password ::instance)
(derive ::image ::instance)
(derive ::font ::instance)

(defmethod ig/pre-init-spec ::instance [_]
  (s/spec int?))

(defmethod ig/init-key ::instance
  [_ permits]
  (Semaphore. (int permits)))

(defn acquire!
  [sem]
  (.acquire ^Semaphore sem))

(defn release!
  [sem]
  (.release ^Semaphore sem))

(defmacro execute
  [rlinst & body]
  `(try
     (acquire! ~rlinst)
     ~@body
     (finally
       (release! ~rlinst))))

