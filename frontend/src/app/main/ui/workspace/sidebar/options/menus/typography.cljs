;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.typography
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.constants :refer [max-input-length]]
   [app.main.data.common :as dcm]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.options.menus.text-shared :refer [text-options]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc typography-advanced-options
  {::mf/wrap [mf/memo]}
  [{:keys [visible? typography editable? name-input-ref on-close on-change on-name-blur
           local? navigate-to-library on-key-down file-id is-asset?]}]
  (let [ref            (mf/use-ref nil)
        font-data      (fonts/get-font-data (:font-id typography))
        typography-id  (:id typography)
        show-actions?  (and is-asset? editable?)

        on-delete
        (mf/use-fn
         (mf/deps typography-id file-id on-close)
         (fn []
           (on-close)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id)
                       (dwl/delete-typography typography-id)
                       (dwl/sync-file file-id file-id :typographies typography-id)
                       (dwu/commit-undo-transaction undo-id)))))

        on-duplicate
        (mf/use-fn
         (mf/deps file-id typography-id)
         (fn []
           (st/emit! (dwl/duplicate-typography file-id typography-id))))]
    (fonts/ensure-loaded! (:font-id typography))

    (mf/use-effect
     (mf/deps visible?)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when visible?
           (dom/scroll-into-view-if-needed! node)))))

    (when visible?
      [:div {:ref ref
             :class (stl/css :advanced-options-wrapper)}

       (if ^boolean editable?
         [:*
          [:div {:class (stl/css :font-name-wrapper)}
           [:div {:class (stl/css :typography-sample-input)
                  :style {:font-family (:font-family typography)
                          :font-weight (:font-weight typography)
                          :font-style (:font-style typography)}}
            (tr "workspace.assets.typography.sample")]

           [:input
            {:class (stl/css :adv-typography-name)
             :type "text"
             :ref name-input-ref
             :default-value (:name typography)
             :max-length max-input-length
             :on-key-down on-key-down
             :on-blur on-name-blur}]

           [:div {:class (stl/css :action-btns)}
            (when show-actions?
              [:*
               [:> icon-button* {:variant "ghost"
                                 :aria-label (tr "workspace.assets.duplicate")
                                 :on-click on-duplicate
                                 :icon i/add}]
               [:> icon-button* {:variant "ghost"
                                 :aria-label (tr "workspace.assets.delete")
                                 :on-click on-delete
                                 :icon i/delete}]])
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "labels.close")
                              :on-click on-close
                              :icon i/tick}]]]

          [:& text-options {:values typography
                            :on-change on-change
                            :show-recent false}]]

         [:div {:class (stl/css :typography-info-wrapper)}
          [:div {:class (stl/css :typography-name-wrapper)}
           [:div {:class (stl/css :typography-sample)

                  :style {:font-family (:font-family typography)
                          :font-weight (:font-weight typography)
                          :font-style (:font-style typography)}}
            (tr "workspace.assets.typography.sample")]

           [:div {:class (stl/css :typography-name)
                  :title (:name typography)}
            (:name typography)]
           [:span {:class (stl/css :typography-font)}
            (:name font-data)]
           [:div {:class (stl/css :action-btn)
                  :on-click on-close}
            deprecated-icon/menu]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.font-style")]
           [:span {:class (stl/css :info-content)} (:font-variant-id typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.font-size")]
           [:span {:class (stl/css :info-content)} (:font-size typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.line-height")]
           [:span {:class (stl/css :info-content)} (:line-height typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.letter-spacing")]
           [:span {:class (stl/css :info-content)} (:letter-spacing typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.text-transform")]
           [:span {:class (stl/css :info-content)} (:text-transform typography)]]

          (when-not local?
            [:a {:class (stl/css :link-btn)
                 :on-click navigate-to-library}
             (tr "workspace.assets.typography.go-to-edit")])])])))

(mf/defc typography-entry
  {::mf/wrap-props false}
  [{:keys [file-id typography local? selected? on-click on-change on-detach on-context-menu editing? renaming? focus-name? external-open* is-asset?]}]
  (let [name-input-ref       (mf/use-ref)
        read-only?           (mf/use-ctx ctx/workspace-read-only?)
        editable?            (and local? (not read-only?))

        open*                (mf/use-state editing?)
        open?                (deref open*)
        font-data            (fonts/get-font-data (:font-id typography))
        name-only?           (= (:name typography) (:name font-data))

        on-name-blur
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [name (dom/get-target-val event)]
             (when-not (str/blank? name)
               (on-change {:name name})
               (st/emit! #(update % :workspace-global dissoc :rename-typography))))))

        on-open
        (mf/use-fn #(reset! open* true))

        on-close
        (mf/use-fn #(reset! open* false))

        navigate-to-library
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (when file-id
             (st/emit! (dcm/go-to-workspace :file-id file-id)))))

        on-key-down
        (mf/use-fn
         (fn [event]
           (let [enter?     (kbd/enter? event)
                 esc?       (kbd/esc? event)
                 input-node (dom/get-target event)]
             (when ^boolean enter?
               (dom/blur! input-node))
             (when ^boolean esc?
               (dom/blur! input-node)))))]

    (mf/with-effect [editing?]
      (when editing?
        (reset! open* editing?)))

    (mf/with-effect [open?]
      (when (some? external-open*)
        (reset! external-open* open?)))

    (mf/with-effect [focus-name?]
      (when focus-name?
        (tm/schedule
         #(when-let [node (mf/ref-val name-input-ref)]
            (dom/focus! node)
            (dom/select-text! node)))))

    [:*
     [:div {:class (stl/css-case :typography-entry true
                                 :selected ^boolean selected?)
            :style {:display (when ^boolean open? "none")}}
      (if renaming?
        [:div {:class (stl/css :font-name-wrapper)}
         [:div
          {:class (stl/css :typography-sample-input)
           :style {:font-family (:font-family typography)
                   :font-weight (:font-weight typography)
                   :font-style (:font-style typography)}}
          (tr "workspace.assets.typography.sample")]

         [:input
          {:class (stl/css :adv-typography-name)
           :type "text"
           :ref name-input-ref
           :default-value (:name typography)
           :max-length max-input-length
           :on-key-down on-key-down
           :on-blur on-name-blur}]]
        [:div
         {:class (stl/css-case :typography-selection-wrapper true
                               :is-selectable ^boolean on-click)
          :on-click on-click
          :on-context-menu on-context-menu}
         [:div
          {:class (stl/css :typography-sample)
           :style {:font-family (:font-family typography)
                   :font-weight (:font-weight typography)
                   :font-style (:font-style typography)}}
          (tr "workspace.assets.typography.sample")]

         [:div {:class (stl/css :typography-name)
                :title (:name typography)} (:name typography)]

         (when-not name-only?
           [:div {:class (stl/css :typography-font)
                  :title (:name font-data)}
            (:name font-data)])])
      [:div {:class (stl/css :element-set-actions)}
       (when ^boolean on-detach
         [:button {:class (stl/css :element-set-actions-button)
                   :on-click on-detach}
          deprecated-icon/detach])
       [:button {:class (stl/css :menu-btn)
                 :on-click on-open}
        deprecated-icon/menu]]]

     [:& typography-advanced-options
      {:visible? open?
       :on-close on-close
       :typography  typography
       :editable? editable?
       :name-input-ref  name-input-ref
       :on-change  on-change
       :on-name-blur on-name-blur
       :on-key-down on-key-down
       :file-id file-id
       :is-asset? is-asset?
       :local?  local?
       :navigate-to-library navigate-to-library}]]))
