;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.nitrate.nitrate-form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.nitrate :as dnt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg*]]
   [app.main.ui.nitrate.nitrate-code-activation-modal]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc nitrate-form-modal*
  {::mf/register modal/components
   ::mf/register-as :nitrate-form
   ::mf/wrap-props true}
  [connectivity]

  (let [online? (:licenses connectivity)
        profile  (mf/deref refs/profile)
        on-click
        (mf/use-fn
         (fn []
           (dnt/go-to-buy-nitrate-license "monthly" dnt/go-to-ac-url)))

        on-activate-click
        (mf/use-fn
         (fn []
           (st/emit! (modal/show {:type :nitrate-code-activation}))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :subscription-success)}
      [:button {:class (stl/css :close-btn) :on-click modal/hide!}
       [:> icon* {:icon-id "close"
                  :size "m"}]]
      [:div {:class (stl/css :modal-success-content)}
       [:div {:class (stl/css :modal-start)}
        ;; TODO this svg is a placeholder. Use the proper one when created
        [:> raw-svg* {:id "nitrate-welcome"}]]

       [:div {:class (stl/css :modal-end)}
        [:div {:class (stl/css :modal-title)}
         (tr "nitrate.form.title")]

        [:p {:class (stl/css :modal-text-large)}
         (tr "nitrate.form.enterprise-intro")]
        [:ul
         [:li {:class (stl/css :modal-text-large)}
          "- " (tr "nitrate.form.enterprise-feature-1")]
         [:li {:class (stl/css :modal-text-large)}
          "- " (tr "nitrate.form.enterprise-feature-2")]
         [:li {:class (stl/css :modal-text-large)}
          "- " (tr "nitrate.form.enterprise-feature-3")]]

        (if online?
          [[:p {:class (stl/css :modal-text-large)}
            (tr "nitrate.form.enterprise.price")]

           [:div {:class (stl/css :modal-text-large :modal-buttons-section)}
            [:div {:class (stl/css :modal-buttons-section)}
             [:> button* {:variant "primary"
                          :on-click on-click
                          :class (stl/css :modal-button)}
              (if (:subscription profile)
                (tr "nitrate.form.upgrade")
                (tr "nitrate.form.try-free"))]
             [:div {:class (stl/css :modal-text-small :modal-info)}
              (tr "nitrate.form.cancel-anytime")]]]

           [:p {:class (stl/css :modal-text-medium)}
            (tr "nitrate.form.have-code") " " [:a {:class (stl/css :link)
                                                   :on-click on-activate-click}
                                               (tr "nitrate.form.enter-code")]]

           [:p {:class (stl/css :modal-text-medium)}
            [:a {:class (stl/css :link) :href dnt/go-to-subscription-url}
             (tr "nitrate.form.see-plan")]]]

          [:div {:class (stl/css :contact)}
           [:p {:class (stl/css :modal-text-large)}
            (if (:subscription profile)
              (tr "nitrate.form.contact-upgrade")
              (tr "nitrate.form.contact-trial"))]
           [:p {:class (stl/css :modal-text-large)}
            [:a {:class (stl/css :link) :href "mailto:sales@penpot.app"}
             "sales@penpot.app"]]
           [:div  {:class (stl/css :activation-code)}
            [:p {:class (stl/css :modal-text-large)}
             (tr "nitrate.form.have-code")]
            [:p {:class (stl/css :modal-text-large)}
             [:a {:class (stl/css :link)
                  :on-click on-activate-click}
              (tr "nitrate.form.enter-code")]]]])]]]]))


