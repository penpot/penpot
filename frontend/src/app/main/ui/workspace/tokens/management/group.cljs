;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.main.ui.workspace.tokens.management.group
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.tokens.management.token-pill :refer [token-pill*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn token-section-icon
  [type]
  (case type
    :border-radius "corner-radius"
    :color "drop"
    :boolean "boolean-difference"
    :font-family "text-font-family"
    :font-size "text-font-size"
    :letter-spacing "text-letterspacing"
    :text-case "text-mixed"
    :text-decoration "text-underlined"
    :font-weight "text-font-weight"
    :typography "text-typography"
    :opacity "percentage"
    :number "number"
    :rotation "rotation"
    :spacing "padding-extended"
    :string "text-mixed"
    :stroke-width "stroke-size"
    :dimensions "expand"
    :sizing "expand"
    "add"))

(mf/defc token-group*
  {::mf/private true}
  [{:keys [type tokens selected-shapes is-selected-inside-layout active-theme-tokens is-open selected-ids]}]
  (let [{:keys [modal title]}
        (get dwta/token-properties type)
        editing-ref  (mf/deref refs/workspace-editor-state)
        not-editing? (empty? editing-ref)

        can-edit?
        (mf/use-ctx ctx/can-edit?)

        tokens
        (mf/with-memo [tokens]
          (vec (sort-by :name tokens)))

        on-context-menu
        (mf/use-fn
         (fn [event token]
           (dom/prevent-default event)
           (st/emit! (dwtl/assign-token-context-menu
                      {:type :token
                       :position (dom/get-client-position event)
                       :errors (:errors token)
                       :token-id (:id token)}))))

        on-toggle-open-click
        (mf/use-fn
         (mf/deps is-open type)
         #(st/emit! (dwtl/set-token-type-section-open type (not is-open))))

        on-popover-open-click
        (mf/use-fn
         (mf/deps type title modal)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dwtl/set-token-type-section-open type true)
                     (let [pos (dom/get-client-position event)]
                       (modal/show (:key modal)
                                   {:x (:x pos)
                                    :y (:y pos)
                                    :position :right
                                    :fields (:fields modal)
                                    :title title
                                    :action "create"
                                    :token-type type})))))

        on-token-pill-click
        (mf/use-fn
         (mf/deps not-editing? selected-ids)
         (fn [event token]
           (dom/stop-propagation event)
           (when (and not-editing? (seq selected-shapes) (not= (:type token) :number))
             (st/emit! (dwta/toggle-token {:token token
                                           :shape-ids selected-ids})))))]

    [:div {:on-click on-toggle-open-click :class (stl/css :token-section-wrapper)}
     [:> cmm/asset-section* {:icon (token-section-icon type)
                             :title title
                             :section :tokens
                             :assets-count (count tokens)
                             :is-open is-open}
      [:> cmm/asset-section-block* {:role :title-button}
       (when can-edit?
         [:> icon-button* {:on-click on-popover-open-click
                           :variant "ghost"
                           :icon i/add
                           :id (str "add-token-button-" title)
                           :aria-label (tr "workspace.tokens.add-token" title)}])]
      (when is-open
        [:> cmm/asset-section-block* {:role :content}
         [:div {:class (stl/css :token-pills-wrapper)}
          (for [token tokens]
            [:> token-pill*
             {:key (:name token)
              :token token
              :selected-shapes selected-shapes
              :is-selected-inside-layout is-selected-inside-layout
              :active-theme-tokens active-theme-tokens
              :on-click on-token-pill-click
              :on-context-menu on-context-menu}])]])]]))
