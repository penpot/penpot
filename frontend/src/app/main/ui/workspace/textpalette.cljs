;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.textpalette
  (:require
   #_[app.util.dom :as dom]
   #_[app.common.data :as d]
   #_[app.common.math :as mth]
   #_[app.main.data.workspace.colors :as mdc]
   #_[app.main.refs :as refs]
   #_[app.main.store :as st]
   #_[app.main.ui.components.color-bullet :as cb]
   #_[app.main.ui.components.dropdown :refer [dropdown]]
   #_[app.main.ui.hooks.resize :refer [use-resize-hook]]
   #_[app.main.ui.icons :as i]
   #_[app.util.color :as uc]
   #_[app.util.i18n :refer [tr]]
   #_[app.util.keyboard :as kbd]
   #_[app.util.object :as obj]
   #_[cuerdas.core :as str]
   #_[goog.events :as events]
   #_[okulary.core :as l]
   [rumext.alpha :as mf]
   ))

(mf/defc textpalette
  []
  [:div])
