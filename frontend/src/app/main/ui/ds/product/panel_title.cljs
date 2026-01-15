;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.panel-title
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:panel-title
  [:map
   [:class {:optional true} :string]
   [:text :string]
   [:on-close {:optional true} fn?]])

(mf/defc panel-title*
  {::mf/schema schema:panel-title}
  [{:keys [class text on-close] :rest props}]
  (let [props
        (mf/spread-props props {:class [class (stl/css :panel-title)]})]

    [:> :div props
     [:span {:class (stl/css :panel-title-text)} text]
     (when on-close
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}])]))
