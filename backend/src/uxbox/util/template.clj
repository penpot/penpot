;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.template
  "A lightweight abstraction over mustache.java template engine.
  The documentation can be found: http://mustache.github.io/mustache.5.html"
  (:require [clojure.walk :as walk]
            [clojure.java.io :as io])
  (:import java.io.StringReader
           java.io.StringWriter
           java.util.HashMap
           com.github.mustachejava.DefaultMustacheFactory
           com.github.mustachejava.Mustache))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  ^DefaultMustacheFactory
  +mustache-factory+ (DefaultMustacheFactory.))

(defprotocol ITemplate
  "A basic template rendering abstraction."
  (-render [template context]))

(extend-type Mustache
  ITemplate
  (-render [template context]
    (with-out-str
      (let [scope (HashMap. (walk/stringify-keys context))]
        (.execute template *out* scope)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-string
  "Render string as mustache template."
  ([^String template]
   (render-string template {}))
  ([^String template context]
   (let [reader (StringReader. template)
         template (.compile +mustache-factory+ reader "example")]
     (-render template context))))

(defn render
  "Load a file from the class path and render
  it using mustache template."
  ([^String path]
   (render path {}))
  ([^String path context]
   (render-string (slurp (io/resource path)) context)))
