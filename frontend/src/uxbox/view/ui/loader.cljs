;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.loader
  (:require [uxbox.builtins.icons :as i]
            [uxbox.view.store :as st]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- Component

(mx/defc loader
  {:mixins [mx/reactive mx/static]}
  []
  (when (mx/react st/loader)
    [:div.loader-content i/loader]))

