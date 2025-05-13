;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.settings
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.file :as ctf]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon  :as i :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc token-settings-modal*
  {::mf/wrap-props false}
  []
  (let [file-data (deref refs/workspace-data)
        value (ctf/get-base-font-size file-data)]
    [:div {:class (stl/css :setting-modal-overlay)
           :data-testid "token-font-settings-modal"}
     [:div {:class (stl/css :setting-modal)}
      [:> icon-button* {:on-click modal/hide!
                        :class (stl/css :close-btn)
                        :icon i/close
                        :variant "action"
                        :aria-label (tr "labels.close")}]

      [:div {:class (stl/css :settings-modal-layout)}
       [:> heading* {:level 2
                     :typography t/headline-medium
                     :class (stl/css :settings-modal-title)}
        "TOKENS SETTINGS"]
       [:div {:class (stl/css :settings-modal-content)}
        [:div {:class (stl/css :settings-modal-subtitle-wrapper)}
         [:> text* {:as "span" :typography t/body-large :class (stl/css :settings-subtitle)}
          "Base font size"]
         [:> icon* {:icon-id "info"}]]
        [:> text* {:as "span" :typography t/body-medium :class (stl/css :settings-modal-description)}
         "Here you will configure the base font size, which will define the value of 1rem."]

        [:> text* {:as "span" :typography t/body-medium :class (stl/css :settings-modal-resolved-value)}
         value]

        [:> text* {:as "span" :typography t/body-small :class (stl/css :settings-modal-resume)}
         "1rem = " value]]]]]))


(mf/defc base-font-size-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/base-font-size}
  []
  [:> token-settings-modal*])