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
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg* raw-svg-list]]
   [app.main.ui.ds.foundations.typography :refer [typography-list]]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.notifications.toast :refer [toast*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.ds.storybook :as sb]
   [app.util.i18n :as i18n]))


(i18n/init! cf/translations)

(def default
  "A export used for storybook"
  #js {:Button button*
       :Heading heading*
       :Icon icon*
       :IconButton icon-button*
       :Input input*
       :Loader loader*
       :RawSvg raw-svg*
       :Select select*
       :Text text*
       :TabSwitcher tab-switcher*
       :Toast toast*
       ;; meta / misc
       :meta #js {:icons (clj->js (sort icon-list))
                  :svgs (clj->js (sort raw-svg-list))
                  :typography (clj->js typography-list)}
       :storybook #js {:StoryGrid sb/story-grid*
                       :StoryGridCell sb/story-grid-cell*
                       :StoryGridRow sb/story-grid-row*
                       :StoryHeader sb/story-header*}})
