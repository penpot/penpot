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
    :font-size "text-font-size"
    :opacity "percentage"
    :number "number"
    :rotation "rotation"
    :spacing "padding-extended"
    :string "text-mixed"
    :stroke-width "stroke-size"
    :typography "text"
    :dimensions "expand"
    :sizing "expand"
    "add"))

(mf/defc token-group*
  {::mf/private true}
  [{:keys [type tokens selected-shapes active-theme-tokens is-open]}]
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
                       :token-name (:name token)}))))

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
                     ;; FIXME: use dom/get-client-position
                     (modal/show (:key modal)
                                 {:x (.-clientX ^js event)
                                  :y (.-clientY ^js event)
                                  :position :right
                                  :fields (:fields modal)
                                  :title title
                                  :action "create"
                                  :token-type type}))))

        on-token-pill-click
        (mf/use-fn
         (mf/deps selected-shapes not-editing?)
         (fn [event token]
           (dom/stop-propagation event)
           (when (and not-editing? (seq selected-shapes))
             (st/emit! (dwta/toggle-token {:token token
                                           :shapes selected-shapes})))))]

    [:div {:on-click on-toggle-open-click :class (stl/css :token-section-wrapper)}
     [:& cmm/asset-section {:icon (token-section-icon type)
                            :title title
                            :section :tokens
                            :assets-count (count tokens)
                            :open? is-open}
      [:& cmm/asset-section-block {:role :title-button}
       (when can-edit?
         [:> icon-button* {:on-click on-popover-open-click
                           :variant "ghost"
                           :icon "add"
                           :aria-label (tr "workspace.tokens.add-token" title)}])]
      (when is-open
        [:& cmm/asset-section-block {:role :content}
         [:div {:class (stl/css :token-pills-wrapper)}
          (for [token tokens]
            [:> token-pill*
             {:key (:name token)
              :token token
              :selected-shapes selected-shapes
              :active-theme-tokens active-theme-tokens
              :on-click on-token-pill-click
              :on-context-menu on-context-menu}])]])]]))
