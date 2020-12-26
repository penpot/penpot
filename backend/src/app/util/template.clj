;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.template
  (:require
   [app.common.exceptions :as ex]
   [selmer.parser :as sp]))

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

