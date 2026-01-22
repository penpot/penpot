;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

 (ns app.main.ui.workspace.tokens.remapping-modal
   "Token remapping confirmation modal"
   (:require-macros [app.main.style :as stl])
   (:require
    [app.main.data.modal :as modal]
    [app.main.store :as st]
    [app.main.ui.ds.buttons.button :refer [button*]]
    [app.main.ui.ds.foundations.typography :as t]
    [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
    [app.main.ui.ds.foundations.typography.text :refer [text*]]
    [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
    [app.util.dom :as dom]
    [app.util.i18n :refer [tr]]
    [rumext.v2 :as mf]))

(defn show-remapping-modal
  "Show the token remapping confirmation modal"
  [{:keys [old-token-name new-token-name references-count on-confirm on-cancel]}]
  (let [props {:old-token-name old-token-name
               :new-token-name new-token-name
               :references-count references-count
               :on-confirm on-confirm
               :on-cancel on-cancel}]
    (st/emit! (modal/show :tokens/remapping-confirmation props))))

(defn hide-remapping-modal
  "Hide the token remapping confirmation modal"
  []
  (st/emit! (modal/hide)))

;; Remapping Modal Component
(mf/defc token-remapping-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :tokens/remapping-confirmation}
  [{:keys [old-token-name new-token-name references-count on-confirm on-cancel]}]
  (let [remapping-in-progress* (mf/use-state false)
        remapping-in-progress? (deref remapping-in-progress*)

        ;; Remap logic on confirm
        on-confirm-remap
        (mf/use-fn
         (mf/deps on-confirm remapping-in-progress*)
         (fn [e]
           (dom/prevent-default e)
           (dom/stop-propagation e)
           (reset! remapping-in-progress* true)
           ;; Call shared remapping logic
           (let [state @st/state
                 remap-modal (:remap-modal state)
                 old-token-name (:old-token-name remap-modal)
                 new-token-name (:new-token-name remap-modal)]
             (st/emit! [:tokens/remap-tokens old-token-name new-token-name]))
           (when (fn? on-confirm)
             (on-confirm))))

        ;; Close modal on Escape key if not in progress
        on-key-down
        (mf/use-fn
         (mf/deps on-cancel remapping-in-progress?)
         (fn [e]
           (when (and (= e.key "Escape")
                      (not remapping-in-progress?))
             (modal/hide!)
             (when (fn? on-cancel)
               (on-cancel)))))

        on-cancel-remap
        (mf/use-fn
         (mf/deps on-cancel)
         (fn [e]
           (dom/prevent-default e)
           (dom/stop-propagation e)
           (modal/hide!)
           (when (fn? on-cancel)
             (on-cancel))))]

    [:div {:class (stl/css :modal-overlay)
           :on-key-down on-key-down
           :role "alertdialog"
           :aria-modal "true"
           :aria-labelledby "modal-title"}

     [:div {:class (stl/css :modal-dialog)
            :data-testid "token-remapping-modal"}
      [:div {:class (stl/css :modal-header)}
       [:> heading* {:level 2
                     :id "modal-title"
                     :typography "headline-large"
                     :class (stl/css :modal-title)}
        (tr "workspace.tokens.remap-token-references-title" old-token-name new-token-name)]]
      [:div {:class (stl/css :modal-content)}
       [:> text* {:as "p" :typography t/body-medium} (tr "workspace.tokens.remap-warning-effects")]
       [:> text* {:as "p" :typography t/body-medium} (tr "workspace.tokens.remap-warning-time")]
       (when remapping-in-progress?
         [:> context-notification*
          {:level :info
           :appearance :ghost}
          (tr "workspace.tokens.remapping-in-progress")])]
      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:on-click on-cancel-remap
                     :type "button"
                     :variant "secondary"
                     :disabled remapping-in-progress?}
         (tr "workspace.tokens.not-remap")]
        [:> button* {:on-click on-confirm-remap
                     :type "button"
                     :variant "primary"
                     :disabled remapping-in-progress?}
         (tr "workspace.tokens.remap")]]]]]))
