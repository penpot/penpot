;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branch-review.layout
  "The frame the compare and conflict dialogs share: a header with the
  route between the two branches, a bar of filters, an
  optional banner, a list beside a detail pane (or a single pane), and a
  footer with a status line and the actions."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.modal :refer [modal* modal-header* modal-footer*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.product.branch-pill :refer [branch-pill*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.pull-requests :as prui]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc route*
  "`from → to` between the branch and main. With `on-swap` the arrow
  turns the comparison around."
  [{:keys [branch-name is-review incoming on-swap]}]
  (let [branch (mf/html [:> branch-pill* {:label branch-name :is-review (boolean is-review)}])
        main   (mf/html [:> branch-pill* {:label (tr "workspace.branches.main") :is-main true}])
        arrow  (if on-swap
                 (mf/html
                  [:button {:class (stl/css :route-swap)
                            :title (tr "workspace.branches.compare.swap")
                            :aria-label (tr "workspace.branches.compare.swap")
                            :on-click on-swap}
                   [:> i/icon* {:icon-id i/arrow-long-right :size "s"}]])
                 (mf/html
                  [:span {:class (stl/css :route-arrow) :aria-hidden true}
                   [:> i/icon* {:icon-id i/arrow-long-right :size "s"}]]))]
    [:div {:class (stl/css :route)}
     (if incoming
       [:* main arrow branch]
       [:* branch arrow main])]))

(mf/defc review-modal*
  "`list` and `detail` are the two panes; with no `list` the `detail`
  takes the whole width. `filters` go in the bar under the header,
  after the \"This branch:\" label; `banner` sits under that bar."
  [{:keys [icon tone title route filters banner list detail
           footer-start footer-end on-close]}]
  [:> modal* {:is-open true
              :on-open-change (prui/on-dismiss on-close)
              :size "xlarge"
              :show-body false
              :class (stl/css :review-modal)}
   [:> modal-header* {:class (stl/css :review-header)}
    [:div {:class (stl/css-case :header-icon true
                                :is-warning (= tone :warning))}
     [:> i/icon* {:icon-id icon}]]
    [:div {:class (stl/css :header-text)}
     [:> heading* {:level 2
                   :typography t/body-large
                   :class (stl/css :header-title)}
      title]
     route]]

   (when filters
     [:div {:class (stl/css :filter-bar)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :filter-label)}
       (tr "workspace.branches.review.this-branch")]
      filters])

   banner

   (if list
     [:div {:class (stl/css :review-body)}
      [:div {:class (stl/css :review-list)} list]
      [:div {:class (stl/css :review-detail)} detail]]
     [:div {:class (stl/css :review-body :is-single)}
      [:div {:class (stl/css :review-detail)} detail]])

   [:> modal-footer* {:variant "split"
                      :class (stl/css :review-footer)
                      :start footer-start
                      :end footer-end}]])

(mf/defc group-head*
  "The heading of a group in the change list: a fold toggle, the group
  name and count, and an optional control before them (a checkbox)."
  [{:keys [label is-open on-toggle start end]}]
  [:div {:class (stl/css :group-head)}
   start
   [:button {:class (stl/css :group-toggle)
             :aria-expanded (boolean is-open)
             :on-click on-toggle}
    [:> i/icon* {:icon-id (if is-open i/arrow-down i/arrow-right)
                 :size "s"
                 :class (stl/css :group-chevron)}]
    [:> text* {:as "span" :typography t/body-small :class (stl/css :group-label)}
     label]]
   end])

(mf/defc notice*
  "A note in the detail pane: the DS contextual notification, with an
  optional title leading the sentence, in the small body type of the
  rest of the pane."
  [{:keys [level title children]}]
  [:> context-notification* {:level level :class (stl/css :notice)}
   [:> text* {:as "span" :typography t/body-small}
    (when title
      [:span {:class (stl/css :notice-title)} title])
    (when title " ")
    children]])

(mf/defc value-chip*
  "A property value in a framed box, with a swatch when it is a colour.
  `is-old` draws the value the change replaced, quieter."
  [{:keys [value is-old]}]
  (let [hex (brc/hex-color value)]
    [:span {:class (stl/css-case :value-chip true :is-old is-old)}
     (when hex
       [:span {:class (stl/css :value-swatch)
               :style {:background-color hex}}])
     (if (and hex (not (sequential? value)))
       hex
       (brc/display-val value))]))

(mf/defc value-steps*
  "Each property of a change as `before → after`, one row per property."
  [{:keys [attrs]}]
  [:div {:class (stl/css :steps)}
   (for [[attr {:keys [main branch]}] attrs]
     [:div {:class (stl/css :steps-row) :key (str attr)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :steps-name)}
       (brc/attr-label attr)]
      [:div {:class (stl/css :steps-values)}
       [:> value-chip* {:value main :is-old true}]
       [:> i/icon* {:icon-id i/arrow-long-right :size "s" :class (stl/css :steps-arrow)}]
       [:> value-chip* {:value branch}]]])])
