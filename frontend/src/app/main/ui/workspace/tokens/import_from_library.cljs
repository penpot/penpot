;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.import-from-library
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))


(mf/defc import-modal-library*
  {::mf/register modal/components
   ::mf/register-as :tokens/import-from-library}
  [all-props]
  (let [{:keys [file-id library-id]}
        (js->clj all-props :keywordize-keys true)

        library-file-ref (mf/with-memo [library-id]
                           (l/derived (fn [state]
                                        (dm/get-in state [:files library-id :data]))
                                      st/state))
        library-data (mf/deref library-file-ref)

        show-libraries-dialog
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (modal/hide!)
           (modal/show! :libraries-dialog {:file-id file-id})))

        cancel
        (mf/use-fn
         (fn []
           (show-libraries-dialog)))

        import
        (mf/use-fn
         (mf/deps file-id library-id library-data)
         (fn []
           (let [tokens-lib (:tokens-lib library-data)]
             (st/emit! (dwtl/import-tokens-lib tokens-lib)))
           (show-libraries-dialog)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :on-click cancel
                        :aria-label (tr "labels.close")
                        :variant "ghost"
                        :icon i/close}]

      [:div {:class (stl/css :modal-header)}
       [:> heading* {:level 2
                     :id "modal-title"
                     :typography "headline-large"
                     :class (stl/css :modal-title)}
        (tr "modals.import-library-tokens.title")]]

      [:div {:class (stl/css :modal-content)}
       [:> text* {:as "p" :typography t/body-medium} (tr "modals.import-library-tokens.description")]]

      [:> context-notification* {:type :context
                                 :appearance "neutral"
                                 :level "default"
                                 :is-html true}
       (tr "workspace.tokens.import-warning")]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:on-click cancel
                     :type "button"
                     :variant "secondary"}
         (tr "labels.cancel")]
        [:> button* {:on-click import
                     :type "button"
                     :variant "primary"}
         (tr "modals.import-library-tokens.import")]]]]]))
