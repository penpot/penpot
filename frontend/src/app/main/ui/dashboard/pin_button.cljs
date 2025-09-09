;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.pin-button
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def ^:private pin-icon
  (deprecated-icon/icon-xref :pin (stl/css :icon)))

(mf/defc pin-button*
  {::mf/props :obj}
  [{:keys [aria-label is-pinned class] :as props}]
  (let [aria-label (or aria-label (tr "dashboard.pin-unpin"))
        class (dm/str (or class "") " " (stl/css-case :button true :button-active is-pinned))

        props (-> (obj/clone props)
                  (obj/unset! "isPinned")
                  (obj/set! "className" class)
                  (obj/set! "aria-label" aria-label))]

    [:> "button" props pin-icon]))
