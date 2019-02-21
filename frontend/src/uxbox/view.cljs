;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view
  (:require [uxbox.config]
            [uxbox.view.locales :as lc]
            [uxbox.view.store :as st]
            [uxbox.view.ui :as ui]))

(defn ^:export init
  []
  (lc/init)
  (st/init)
  (ui/init-routes)
  (ui/init))
