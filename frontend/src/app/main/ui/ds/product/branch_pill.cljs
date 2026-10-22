;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.branch-pill
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [rumext.v2 :as mf]))

(def ^:private schema:branch-pill
  [:map
   [:class {:optional true} [:maybe :string]]
   [:label :string]
   [:is-main {:optional true} [:maybe :boolean]]
   [:is-review {:optional true} [:maybe :boolean]]])

(mf/defc branch-pill*
  "A branch name inside running text, as a static pill. Main is drawn
  muted; any other branch in the accent color, or in the info blue when
  it is the branch of a pull request under review."
  {::mf/schema schema:branch-pill}
  [{:keys [class label is-main is-review] :rest props}]
  (let [is-main   (d/nilv is-main false)
        is-review (d/nilv is-review false)
        props     (mf/spread-props props
                                   {:class [class (stl/css-case :branch-pill true
                                                                :is-main is-main
                                                                :is-review (and is-review (not is-main)))]})]
    [:> :span props
     [:> icon* {:icon-id (if is-main i/git-commit-vertical i/git-branch)
                :size "s"
                :class (stl/css :icon)}]
     [:> text* {:as "span"
                :typography t/body-small
                :class (stl/css :label)}
      label]]))
