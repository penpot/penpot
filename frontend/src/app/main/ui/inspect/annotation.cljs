;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.annotation
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc annotation
  [{:keys [content] :as props}]
  [:div {:class (stl/css :attributes-block)}
   [:& title-bar {:collapsable false
                  :title       (tr "workspace.options.component.annotation")
                  :class       (stl/css :title-spacing-annotation)}
    [:> copy-button* {:data content
                      :class (stl/css :copy-btn-title)}]]

   [:div {:class (stl/css :annotation-content)} content]])
