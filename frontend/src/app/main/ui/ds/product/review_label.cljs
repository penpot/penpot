;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.review-label
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(def ^:private schema:review-label
  [:map
   [:class {:optional true} [:maybe :string]]
   [:label :string]])

(mf/defc review-label*
  "Says that a comment or a task belongs to a design review, with the name
  of the review."
  {::mf/schema schema:review-label}
  [{:keys [class label] :rest props}]
  (let [props (mf/spread-props props
                               {:class [class (stl/css :review-label)]
                                :title label})]
    [:> :span props
     [:> icon* {:icon-id i/git-pull-request-arrow :size "s"}]
     [:span {:class (stl/css :text)} label]]))
