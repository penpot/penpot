;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds
  (:require
   [app.main.ui.ds.buttons.simple-button :refer [simple-button]]
   [app.main.ui.ds.storybook :as sb]))

(def default
  "A export used for storybook"
  #js {:SimpleButton simple-button
       :StoryWrapper sb/story-wrapper})
