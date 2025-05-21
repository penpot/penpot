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
   [app.main.data.workspace.tokens.typography :as wtt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon  :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))


(mf/defc token-settings-modal*
  {::mf/wrap-props false}
  []
  (let [file-data (deref refs/workspace-data)
        base-font-size* (mf/use-state #(ctf/get-base-font-size file-data))
        base-font-size (deref base-font-size*)
        valid?* (mf/use-state true)
        is-valid (deref valid?*)

        is-valid?
        (fn [value]
          (boolean (re-matches #"^\d+(\.\d+)?(px)?$" value)))

        hint-message (if is-valid
                       (str "1rem = " base-font-size)
                       (tr "workspace.tokens.base-font-size.error"))

        on-change-base-font-size
        (mf/use-fn
         (mf/deps base-font-size*)
         (fn [e]
           (let [value (dom/get-target-val e)]
             (reset! valid?* (is-valid? value))
             (when (is-valid? value)
               (let [unit-value (if (str/ends-with? value "px")
                                  value
                                  (str value "px"))]
                 (reset! base-font-size* unit-value))))))

        on-set-font
        (mf/use-fn
         (mf/deps base-font-size)
         (fn []
           (st/emit! (wtt/set-base-font-size base-font-size)
                     (modal/hide))))

        handle-key-down
        (mf/use-fn
         (mf/deps base-font-size is-valid)
         (fn [e]
           (when (and (k/enter? e) is-valid)
             (on-set-font))))]

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
        (tr "workspace.tokens.settings")]

       [:div {:class (stl/css :settings-modal-content)}
        [:div {:class (stl/css :settings-modal-subtitle-wrapper)}
         [:> text* {:as "span" :typography t/body-large :class (stl/css :settings-subtitle)}
          (tr "workspace.tokens.base-font-size")]]
        [:> text* {:as "span" :typography t/body-medium :class (stl/css :settings-modal-description)}
         (tr "workspace.tokens.setting-description")]

        [:> input* {:type "text"
                    :placeholder "16"
                    :default-value base-font-size
                    :hint-message hint-message
                    :hint-type (if is-valid "hint" "error")
                    :on-key-down handle-key-down
                    :on-change on-change-base-font-size}]

        [:div {:class (stl/css :settings-modal-actions)}
         [:> button* {:on-click modal/hide!
                      :variant "secondary"}
          (tr "labels.cancel")]
         [:> button* {:on-click on-set-font
                      :disabled (not is-valid)
                      :variant "primary"}
          (tr "labels.save")]]]]]]))


(mf/defc base-font-size-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/base-font-size}
  []
  [:> token-settings-modal*])
