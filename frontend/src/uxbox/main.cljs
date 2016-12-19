;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main
  (:require [uxbox.store :as st]
            [uxbox.main.locales :as lc]
            [uxbox.main.ui :as ui]
            [uxbox.main.state :refer [initial-state]]))

(defn ^:export init
  []
  (lc/init)
  (st/init initial-state)
  (ui/init-routes)
  (ui/init))
