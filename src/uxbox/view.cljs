;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view
  (:require [uxbox.config]
            [uxbox.main.state :as st]
            [uxbox.view.ui :as ui]))

(defn initial-state
  []
  {:route nil
   :project nil
   :pages nil
   :page nil
   :flags #{:sitemap}
   :shapes-by-id {}
   :pages-by-id {}})

(defn ^:export init
  []
  (st/init initial-state)
  (ui/init-routes)
  (ui/init))
