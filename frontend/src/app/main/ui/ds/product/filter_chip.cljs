;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.filter-chip
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.product.tag :as tag]
   [rumext.v2 :as mf]))

(def ^:private schema:filter-chip
  [:map
   [:class {:optional true} [:maybe :string]]
   [:label :string]
   [:icon {:optional true} [:maybe [:and :string [:fn #(contains? i/icon-list %)]]]]
   [:tone {:optional true} [:maybe (into [:enum] tag/tones)]]
   [:is-selected {:optional true} [:maybe :boolean]]])

(mf/defc filter-chip*
  "A rounded toggle that filters a list by one category, with an
  optional icon before its label. Selected, a toned chip draws its
  outline, text and icon in its tone; a neutral one in the accent
  colour."
  {::mf/schema schema:filter-chip}
  [{:keys [class label icon tone is-selected] :rest props}]
  (let [tone        (d/nilv tone "neutral")
        is-selected (d/nilv is-selected false)
        props       (mf/spread-props props
                                     {:class [class (stl/css-case :filter-chip true
                                                                  :is-selected is-selected
                                                                  :tone-accent (= tone "accent")
                                                                  :tone-info (= tone "info")
                                                                  :tone-warning (= tone "warning")
                                                                  :tone-error (= tone "error")
                                                                  :tone-success (= tone "success")
                                                                  :tone-purple (= tone "purple"))]
                                      :type "button"
                                      :aria-pressed is-selected})]
    [:> :button props
     (when icon
       [:> icon* {:icon-id icon :size "s" :class (stl/css :icon)}])
     label]))
