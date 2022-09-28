;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.loader
  (:require
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

;; --- Component

(mf/defc loader
  []
  (when (mf/deref st/loader)
    [:div.loader-content i/loader-pencil]))
