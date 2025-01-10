;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.notifications.toast
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :as i]
   [rumext.v2 :as mf]))

(def ^:private icons-by-level
  {"info" i/info
   "warning" i/msg-neutral
   "error" i/delete-text
   "success" i/status-tick})

(def ^:private schema:toast
  [:map
   [:class {:optional true} :string]
   [:level {:optional true}
    [:maybe [:enum "info" "warning" "error" "success"]]]
   [:on-close {:optional true} fn?]])

(mf/defc toast*
  {::mf/props :obj
   ::mf/schema schema:toast}
  [{:keys [class level children on-close] :rest props}]
  (let [class (dm/str (stl/css-case :toast true
                                    :toast-info (= level "info")
                                    :toast-warning (= level "warning")
                                    :toast-error (= level "error")
                                    :toast-success (= level "success")) " " class)
        icon-id (or (get icons-by-level level) i/msg-neutral)
        props (mf/spread-props props {:class class})]
    [:> "aside" props
     [:*
      [:> i/icon* {:icon-id icon-id :class (stl/css :icon)}]
      children
      ;; TODO: this should be a buttom from the DS, but this variant is not designed yet.
      ;; https://tree.taiga.io/project/penpot/task/8492
      [:> "button" {:on-click on-close :aria-label "Close" :class (stl/css :close-button)} [:> i/icon* {:icon-id i/close}]]]]))
