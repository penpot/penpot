;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.pin-button
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl]
   [app.main.ui.icons :refer [icon-xref]])
  (:require
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def pin-icon (icon-xref :pin-refactor (stl/css :icon)))

(mf/defc pin-button*
  {::mf/props :obj}
  [{:keys [aria-label is-pinned class] :as props}]
  (let [aria-label (or aria-label (tr "dashboard.pin-unpin"))
        class (dm/str (or class "") " " (stl/css-case :button true :button-active is-pinned))
        props (mf/spread-props props {:class class
                                      :aria-label aria-label})]
    [:> "button" props pin-icon]))