;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.branch-review.layout
  "The small pieces the list and the detail of a comparison share: the
  heading of a group, a note, and a property value alone or as `before →
  after`."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [rumext.v2 :as mf]))

(mf/defc group-head*
  "The heading of a group in a list: a fold toggle, the group name and
  count with an optional `icon` before them, and an optional control at
  each end (a checkbox at the `start`)."
  [{:keys [label icon is-open on-toggle start end]}]
  [:div {:class (stl/css :group-head)}
   start
   [:button {:class (stl/css :group-toggle)
             :aria-expanded (boolean is-open)
             :on-click on-toggle}
    [:> i/icon* {:icon-id (if is-open i/arrow-down i/arrow-right)
                 :size "s"
                 :class (stl/css :group-chevron)}]
    (when icon
      [:> i/icon* {:icon-id icon :size "s" :class (stl/css :group-chevron)}])
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
  "Each property of a change as `before → after`, the name of the
  property over its two values. `incoming` says the change is the
  source's, going to the branch: its new value is then the source's."
  [{:keys [attrs incoming]}]
  [:div {:class (stl/css :steps)}
   (for [[attr {:keys [source branch]}] attrs]
     [:div {:class (stl/css :steps-row) :key (str attr)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :steps-name)}
       (brc/attr-label attr)]
      [:div {:class (stl/css :steps-values)}
       [:> value-chip* {:value (if incoming branch source) :is-old true}]
       [:> i/icon* {:icon-id i/arrow-long-right :size "s" :class (stl/css :steps-arrow)}]
       [:> value-chip* {:value (if incoming source branch)}]]])])
