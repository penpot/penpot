;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.template
  (:require
   [app.common.exceptions :as ex]
   [cuerdas.core :as str]
   [selmer.filters :as sf]
   [selmer.parser :as sp]))

;; (sp/cache-off!)

(sf/add-filter! :abbreviate
                (fn [s n]
                  (let [n (parse-long n)]
                    (str/abbreviate s n))))


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

