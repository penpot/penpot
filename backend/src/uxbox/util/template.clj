;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.template
  "A lightweight abstraction over mustache.java template engine.
  The documentation can be found: http://mustache.github.io/mustache.5.html"
  (:require
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [uxbox.common.exceptions :as ex])
  (:import
   java.io.StringReader
   java.util.HashMap
   java.util.function.Function;
   com.github.mustachejava.DefaultMustacheFactory
   com.github.mustachejava.Mustache))

(def ^DefaultMustacheFactory +mustache-factory+ (DefaultMustacheFactory.))

(defn- adapt-context
  [data]
  (walk/postwalk (fn [x]
                   (cond
                     (instance? clojure.lang.Named x)
                     (str/camel (name x))

                     (instance? clojure.lang.MapEntry x)
                     x

                     (fn? x)
                     (reify Function
                       (apply [this content]
                         (try
                           (x content)
                           (catch Exception e
                             (log/error e "Error on executing" x)
                             ""))))

                     (or (vector? x) (list? x))
                     (java.util.ArrayList. ^java.util.List x)

                     (map? x)
                     (java.util.HashMap. ^java.util.Map x)

                     (set? x)
                     (java.util.HashSet. ^java.util.Set x)

                     :else
                     x))
                 data))


(defn render
  [path context]
  (try
    (let [context (adapt-context context)
          template (.compile +mustache-factory+ path)]
      (with-out-str
        (let [scope (HashMap. ^java.util.Map (walk/stringify-keys context))]
          (.execute ^Mustache template *out* scope))))
    (catch Exception cause
      (ex/raise :type :internal
                :code :template-render-error
                :cause cause))))

