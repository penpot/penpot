;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.icons
  (:require [rumext.alpha]))

(defmacro icon-xref
  [id]
  (let [href (str "#icon-" (name id))]
    `(rumext.alpha/html
      [:svg {:width 500 :height 500}
       [:use {:xlinkHref ~href}]])))

