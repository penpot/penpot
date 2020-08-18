;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.util.template
  "A lightweight abstraction over mustache.java template engine.
  The documentation can be found: http://mustache.github.io/mustache.5.html"
  (:require
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [selmer.parser :as sp]
   [app.common.exceptions :as ex]))

;; (sp/cache-off!)

(defn render
  [path context]
  (try
    (sp/render-file path context)
    (catch Exception cause
      (ex/raise :type :internal
                :code :template-render-error
                :cause cause))))

(defn render-string
  [content context]
  (try
    (sp/render content context)
    (catch Exception cause
      (ex/raise :type :internal
                :code :template-render-error
                :cause cause))))

