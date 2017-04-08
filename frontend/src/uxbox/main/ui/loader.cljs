;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.loader
  (:require [uxbox.main.store :as st]
            [uxbox.builtins.icons :as i]
            [rumext.core :as mx :include-macros true]))

;; --- Component

(mx/defc loader
  {:mixins [mx/reactive mx/static]}
  []
  (when (mx/react st/loader)
    [:div.loader-content i/loader]))
