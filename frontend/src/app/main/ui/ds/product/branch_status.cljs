;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.branch-status
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:branch-status
  [:map
   [:class {:optional true} [:maybe :string]]
   [:ahead {:optional true} [:maybe :int]]
   [:behind {:optional true} [:maybe :int]]
   [:has-error {:optional true} [:maybe :boolean]]
   [:conflicts {:optional true} [:maybe :int]]
   [:review {:optional true} [:maybe [:enum "pending" "approved"]]]
   [:approvals {:optional true} [:maybe :int]]
   [:reviewers {:optional true} [:maybe :int]]])

(mf/defc branch-status*
  "How a branch stands against main: the changes it has and lacks, and one
  pill. Conflicts take the pill over the review state, because they block
  the merge whatever the reviewers say. A comparison that failed shows a
  warning instead of the counts, since zeros would read as in sync."
  {::mf/schema schema:branch-status}
  [{:keys [class ahead behind has-error conflicts review approvals reviewers] :rest props}]
  (let [ahead     (d/nilv ahead 0)
        behind    (d/nilv behind 0)
        conflicts (d/nilv conflicts 0)
        approvals (d/nilv approvals 0)
        reviewers (d/nilv reviewers 0)
        props     (mf/spread-props props
                                   {:class [class (stl/css :branch-status)]})]
    [:> :div props
     (if ^boolean has-error
       [:span {:class (stl/css :error)
               :title (tr "workspace.branches.counts.error")}
        [:> icon* {:icon-id i/triangle-alert :size "s"}]]
       [:*
        (when (pos? ahead)
          [:span {:class (stl/css :count)
                  :title (tr "workspace.branches.counts.ahead")}
           [:> icon* {:icon-id i/arrow-up :size "s"}]
           (dm/str ahead)])
        (when (pos? behind)
          [:span {:class (stl/css :count)
                  :title (tr "workspace.branches.counts.behind")}
           [:> icon* {:icon-id i/arrow-down :size "s"}]
           (dm/str behind)])])

     (cond
       (pos? conflicts)
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css :pill :pill-conflict)
                  :title (tr "workspace.branches.banner.conflicts" (dm/str conflicts))}
        [:> icon* {:icon-id i/triangle-alert :size "s"}]
        (dm/str conflicts)]

       (some? review)
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css-case :pill true
                                       :pill-pending (= review "pending")
                                       :pill-approved (= review "approved"))
                  :title (tr "workspace.pull-requests.info.approvals")}
        [:> icon* {:icon-id i/git-pull-request-arrow :size "s"}]
        (when (pos? reviewers)
          (dm/str approvals "/" reviewers))])]))
