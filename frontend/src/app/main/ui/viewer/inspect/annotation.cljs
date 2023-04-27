;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.annotation
  (:require
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc annotation
  [{:keys [content] :as props}]
  [:div.attributes-block.inspect-annotation
   [:div.attributes-block-title
    [:div.attributes-block-title-text (tr "workspace.options.component.annotation")]
    [:& copy-button {:data content}]]
   [:div.content content]])
