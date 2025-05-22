;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds
  (:require
   [app.config :as cf]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.combobox :refer [combobox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.utilities.hint-message :refer [hint-message*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg* raw-svg-list]]
   [app.main.ui.ds.foundations.typography :refer [typography-list]]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.foundations.utilities.token.token-status :refer [token-status-icon*
                                                                    token-status-list]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.notifications.actionable :refer [actionable*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.notifications.shared.notification-pill :refer [notification-pill*]]
   [app.main.ui.ds.notifications.toast :refer [toast*]]
   [app.main.ui.ds.product.autosaved-milestone :refer [autosaved-milestone*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.cta :refer [cta*]]
   [app.main.ui.ds.product.empty-placeholder :refer [empty-placeholder*]]
   [app.main.ui.ds.product.input-with-meta :refer [input-with-meta*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.ds.product.user-milestone :refer [user-milestone*]]
   [app.main.ui.ds.storybook :as sb]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.main.ui.ds.utilities.date :refer [date*]]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.util.i18n :as i18n]
   [rumext.v2 :as mf]))


(i18n/init! cf/translations)

(def default
  "A export used for storybook"
  (mf/object
   {:Button button*
    :Heading heading*
    :Icon icon*
    :IconButton icon-button*
    :Input input*
    :Label label*
    :InputField input-field*
    :HintMessage hint-message*
    :InputWithMeta input-with-meta*
    :EmptyPlaceholder empty-placeholder*
    :Loader loader*
    :RawSvg raw-svg*
    :Select select*
    :Combobox combobox*
    :Text text*
    :TabSwitcher tab-switcher*
    :Toast toast*
    :Tooltip tooltip*
    :ContextNotification context-notification*
    :NotificationPill notification-pill*
    :Actionable actionable*
    :TokenStatusIcon token-status-icon*
    :Swatch swatch*
    :Cta cta*
    :Avatar avatar*
    :AutosavedMilestone autosaved-milestone*
    :UserMilestone user-milestone*
    :Date date*
    ;; meta / misc
    :meta
    {:icons (clj->js (sort icon-list))
     :tokenStatus (clj->js (sort token-status-list))
     :svgs (clj->js (sort raw-svg-list))
     :typography (clj->js typography-list)}
    :storybook
    {:StoryGrid sb/story-grid*
     :StoryGridCell sb/story-grid-cell*
     :StoryGridRow sb/story-grid-row*
     :StoryHeader sb/story-header*}}))
