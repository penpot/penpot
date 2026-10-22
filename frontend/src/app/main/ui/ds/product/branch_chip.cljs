;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.branch-chip
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [rumext.v2 :as mf]))

(def ^:private schema:branch-chip
  [:map
   [:class {:optional true} [:maybe :string]]
   [:label :string]
   [:is-open {:optional true} [:maybe :boolean]]])

(mf/defc branch-chip*
  "The button that names a branch and opens the branch switcher. It only
  draws the trigger: the popover it opens belongs to the caller."
  {::mf/schema schema:branch-chip}
  [{:keys [class label is-open] :rest props}]
  (let [is-open (d/nilv is-open false)
        props   (mf/spread-props props
                                 {:class [class (stl/css-case :branch-chip true
                                                              :is-open is-open)]
                                  :type "button"
                                  :aria-haspopup "dialog"
                                  :aria-expanded is-open})]
    [:> :button props
     [:> icon* {:icon-id i/git-branch
                :size "s"
                :class (stl/css :icon)}]
     [:> text* {:as "span"
                :typography t/body-small
                :class (stl/css :label)}
      label]
     [:> icon* {:icon-id i/arrow-down
                :size "s"
                :class (stl/css :icon)}]]))
