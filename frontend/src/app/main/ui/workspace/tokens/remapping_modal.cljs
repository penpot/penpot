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
    [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
    [app.main.ui.ds.foundations.assets.icon :as i]
    [app.main.ui.ds.foundations.typography :as t]
    [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
    [app.main.ui.ds.foundations.typography.text :refer [text*]]
    [app.util.i18n :refer [tr]]
    [app.util.keyboard :as kbd]
    [rumext.v2 :as mf]))

(defn- hide-remapping-modal
  "Hide the token remapping confirmation modal"
  []
  (st/emit! (modal/hide)))

;;  TODO: Uncomment when modal components support schema validation

;; (def ^:private schema:remap-data
;;   [:map
;;    [:old-name :string]
;;    [:new-name :string]
;;    [:type [:enum "token" "node"]]])

;; (def ^:private schema:token-remapping-modal
;;   [:map
;;    [:remap-data [:maybe schema:remap-data]]
;;    [:on-remap {:optional true} [:maybe fn?]]
;;    [:on-rename {:optional true} [:maybe fn?]]])

;; Remapping Modal Component
(mf/defc token-remapping-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/remapping-confirmation
   ;;  TODO: Uncomment when modal components support schema validation
   ;;  ::mf/schema schema:token-remapping-modal
   }
  [{:keys [remap-data on-remap on-rename]}]
  (let [old-name (:old-name remap-data)
        new-name (:new-name remap-data)

        ;; Remap logic on confirm
        confirm-remap
        (mf/use-fn
         (mf/deps on-remap old-name new-name)
         (fn []
           ;; Call shared remapping logic
           ;;  (st/emit! [:tokens/remap-tokens old-name new-name])
           (when (fn? on-remap)
             (on-remap))))

        rename-token
        (mf/use-fn
         (mf/deps on-rename)
         (fn []
           (when (fn? on-rename)
             (on-rename))))

        cancel-action
        (mf/use-fn
         (fn []
           (hide-remapping-modal)))

        ;; Close modal on Escape key if not in progress
        on-key-down
        (mf/use-fn
         (mf/deps cancel-action)
         (fn [event]
           (when (kbd/enter? event)
             (cancel-action))))]

    [:div {:class (stl/css :modal-overlay)
           :on-key-down on-key-down
           :role "alertdialog"
           :aria-modal "true"
           :aria-labelledby "modal-title"}

     [:div {:class (stl/css :modal-dialog)
            :data-testid "token-remapping-modal"}
      [:> icon-button* {:on-click cancel-action
                        :class (stl/css :close-btn)
                        :icon i/close
                        :variant "action"
                        :aria-label (tr "labels.close")}]

      [:div {:class (stl/css :modal-header)}
       [:> heading* {:level 2
                     :id "modal-title"
                     :typography "headline-large"
                     :class (stl/css :modal-title)}
        (if (= (:type remap-data) "token")
          (tr "workspace.tokens.remap-token-references-title" old-name new-name)
          (tr "workspace.tokens.remap-node-references-title" old-name new-name))]]
      [:div {:class (stl/css :modal-content)}
       (if (= (:type remap-data) "token")
         [:> text* {:as "p" :typography t/body-medium} (tr "workspace.tokens.remap-token-warning-effects")]
         [:> text* {:as "p" :typography t/body-medium} (tr "workspace.tokens.remap-node-warning-effects")])
       [:> text* {:as "p" :typography t/body-medium} (tr "workspace.tokens.remap-warning-time")]]
      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:on-click rename-token
                     :type "button"
                     :variant "secondary"}
         (tr "workspace.tokens.not-remap")]
        [:> button* {:on-click confirm-remap
                     :type "button"
                     :variant "primary"}
         (tr "workspace.tokens.remap")]]]]]))
