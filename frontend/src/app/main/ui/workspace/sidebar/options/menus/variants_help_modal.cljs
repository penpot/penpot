
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.variants-help-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc variants-help-modal
  {::mf/register modal/components
   ::mf/register-as :variants-help-modal}
  []
  (let [on-close
        (mf/use-fn
         (fn [] (modal/hide!)))

        close-dialog-outside
        (mf/use-fn
         (fn [event]
           (when (= (dom/get-target event) (dom/get-current-target event))
             (modal/hide!))))]

    [:div {:class (stl/css :modal-overlay)
           :on-click close-dialog-outside}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:on-click on-close
                        :class (stl/css :modal-close-btn)
                        :icon i/close
                        :variant "action"
                        :aria-label (tr "labels.close")}]
      [:> heading* {:level 2
                    :typography "headline-medium"
                    :class (stl/css :modal-title)}
       (tr "workspace.options.component.variants-help-modal.title")]

      [:div {:class (stl/css :modal-content)}

       [:div {:class (stl/css :help-text)}

        [:> text* {:typography t/body-large}
         (tr "workspace.options.component.variants-help-modal.intro")]

        [:ul {:class (stl/css :rule-list)}
         [:li {:class (stl/css :rule-item)}
          [:div {:class (stl/css :rule-item-icon)}
           [:> icon* {:icon-id i/text-mixed
                      :size "m"
                      :aria-hidden true}]]

          [:div {:class (stl/css :rule-item-text)}
           [:> text* {:as "span"
                      :typography t/body-large
                      :class (stl/css :rule-item-highlight)}
            (tr "workspace.options.component.variants-help-modal.rule1")]]]
         [:li {:class (stl/css :rule-item)}
          [:div {:class (stl/css :rule-item-icon)}
           [:> icon* {:icon-id i/img
                      :size "m"
                      :aria-hidden true}]]

          [:> text* {:typography t/body-large
                     :class (stl/css :rule-item-text)}
           [:span {:class (stl/css :rule-item-highlight)} (tr "workspace.options.component.variants-help-modal.rule2")]
           (tr "workspace.options.component.variants-help-modal.rule2.detail")]]

         [:li {:class (stl/css :rule-item)}
          [:div {:class (stl/css :rule-item-icon)}
           [:> icon* {:icon-id i/folder
                      :size "m"
                      :aria-hidden true}]]

          [:> text* {:class (stl/css :rule-item-text)
                     :typography t/body-large}
           [:span {:class (stl/css :rule-item-highlight)} (tr "workspace.options.component.variants-help-modal.rule3")]
           (tr "workspace.options.component.variants-help-modal.rule3.detail")]]]

        [:> text* {:typography t/body-large}
         (tr "workspace.options.component.variants-help-modal.outro")]]

       [:div {:class (stl/css :help-image)}
        [:img {:src "images/help-variant-connection.png"
               :alt ""}]]]

      [:div {:class (stl/css :button-row)}
       [:> button* {:variant "primary"
                    :type "button"
                    :class (stl/css :modal-accept-btn)
                    :on-click on-close}
        (tr "ds.confirm-ok")]]]]))
