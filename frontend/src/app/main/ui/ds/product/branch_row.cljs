;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.branch-row
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [rumext.v2 :as mf]))

(def ^:private schema:branch-row
  [:map
   [:class {:optional true} [:maybe :string]]
   [:label :string]
   [:is-current {:optional true} [:maybe :boolean]]])

(mf/defc branch-row*
  "A branch in a short list, as a button that opens it. The current one is
  marked and cannot be opened again. `children` go to the end of the row:
  usually a `branch-status*`."
  {::mf/schema schema:branch-row}
  [{:keys [class label is-current children] :rest props}]
  (let [is-current (d/nilv is-current false)
        props      (mf/spread-props props
                                    {:class [class (stl/css-case :branch-row true
                                                                 :is-current is-current)]
                                     :type "button"
                                     :disabled is-current
                                     :aria-current (when is-current "true")})]
    [:> :button props
     [:> icon* {:icon-id (if is-current i/tick i/git-branch)
                :size "s"
                :class (stl/css :icon)}]
     [:> text* {:as "span"
                :typography t/body-small
                :class (stl/css :label)}
      label]
     (when (some? children)
       [:span {:class (stl/css :aside)}
        children])]))
