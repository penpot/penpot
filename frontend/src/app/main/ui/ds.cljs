;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds
  (:require
   [app.main.ui.ds.foundations.icon :refer [icon* icon-list]]
   [app.main.ui.ds.storybook :as sb]))

(def default
  "A export used for storybook"
  #js {:Icon icon*
       ;; meta / misc
       :meta #js {:icons icon-list}
       :storybook #js {:StoryWrapper sb/story-wrapper* :IconGrid sb/icon-grid*}})
