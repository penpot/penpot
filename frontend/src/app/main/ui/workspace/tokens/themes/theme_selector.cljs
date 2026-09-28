;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.tokens.themes.theme-selector
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc themes-list*
  [{:keys [themes tokens-status on-close is-grouped]}]
  (when (seq themes)
    [:ul {:class (stl/css :theme-options)}
     (for [[_ {:keys [id name] :as theme}] themes
           :let [selected? (ctos/theme-active? tokens-status id)
                 select-theme (fn [e]
                                (dom/stop-propagation e)
                                (st/emit! (dwtl/toggle-token-theme-active id))
                                (on-close))]]
       [:li {:key (str "theme-" id)
             :role "option"
             :aria-selected selected?
             :class (stl/css-case
                     :checked-element true
                     :sub-item is-grouped
                     :is-selected selected?)
             :on-click select-theme}
        [:> text* {:as "span" :typography "body-small" :class (stl/css :label) :title name} name]
        [:> icon* {:icon-id i/tick
                   :aria-hidden true
                   :class (stl/css-case :check-icon true
                                        :check-icon-visible selected?)}]])]))

(mf/defc theme-options*
  [{:keys [tokens-lib tokens-status tokens-source can-edit-tokens team-id on-close]}]
  (let [themes
        (mf/with-memo [tokens-lib]
          (ctob/get-theme-tree-no-hidden tokens-lib))

        edit-token-themes
        (fn []
          (if can-edit-tokens
            (modal/show! :tokens/themes {})
            (st/emit! (rt/nav :workspace
                              {:team-id team-id
                               :file-id tokens-source
                               :layout :tokens}
                              ::rt/new-window true))))]

    [:ul {:class (stl/css :theme-options :custom-select-dropdown)
          :role "listbox"}
     (for [[group themes] themes]
       [:li {:key group
             :aria-labelledby (dm/str group "-label")
             :role "group"}
        (when (seq group)
          [:> text* {:as "span" :typography "headline-small" :class (stl/css :group) :id (dm/str (str/kebab group) "-label") :title group} group])
        [:> themes-list* {:themes themes
                          :tokens-status tokens-status
                          :on-close on-close
                          :is-grouped true}]])
     [:li {:class (stl/css :separator)
           :aria-hidden true}]
     [:li {:class (stl/css-case :checked-element true
                                :checked-element-button true)
           :role "option"
           :on-click edit-token-themes}
      [:> text* {:as "span" :typography "body-small"} (tr "workspace.tokens.edit-themes")]
      [:> icon* {:icon-id (if can-edit-tokens
                            i/arrow-right
                            i/open-link)
                 :aria-hidden true}]]]))

(mf/defc theme-selector*
  [{:keys [tokens-source]}]
  (let [;; Store
        tokens-lib      (mf/use-ctx ctx/tokens-lib)
        tokens-status   (mf/use-ctx ctx/tokens-status)
        can-edit-file?  (mf/use-ctx ctx/can-edit?)
        can-edit-tokens (mf/use-ctx ctx/can-edit-tokens?)
        team-id         (mf/use-ctx ctx/current-team-id)

        active-themes
        (mf/with-memo [tokens-lib tokens-status]
          (cfo/get-active-themes tokens-status tokens-lib))

        active-themes-count
        (mf/with-memo [active-themes]
          (count active-themes))

        ;; Data
        current-label (cond
                        (> active-themes-count 1) (tr "workspace.tokens.active-themes" active-themes-count)
                        (= active-themes-count 1) (-> (first active-themes)
                                                      (ctob/get-theme-path true))
                        :else (tr "workspace.tokens.no-active-theme"))

        ;; State
        state* (mf/use-state
                #(do {:id (uuid/next)
                      :is-open? false
                      :rect nil}))
        state (deref state*)
        is-open? (:is-open? state)
        rect (:rect state)

        ;; Dropdown
        on-close-dropdown (mf/use-fn #(swap! state* assoc :is-open? false))

        on-open-dropdown
        (mf/use-fn
         (mf/deps can-edit-file?)
         (fn [event]
           (when can-edit-file?
             (when-let [node (dom/get-current-target event)]
               (let [rect (dom/get-bounding-rect node)]
                 (swap! state* assoc
                        :is-open? true
                        :rect rect))))))

        container (hooks/use-portal-container :popup)]

    [:div {:on-click on-open-dropdown
           :disabled (not can-edit-file?)
           :aria-expanded is-open?
           :aria-haspopup "listbox"
           :tab-index "0"
           :role "combobox"
           :data-testid "theme-select"
           :class (stl/css-case :custom-select true
                                :disabled-select (not can-edit-file?))}
     [:> text* {:as "span" :typography "body-small" :class (stl/css :current-label)}
      current-label]
     [:> icon* {:icon-id i/arrow-down :class (stl/css :dropdown-button) :aria-hidden true}]

     (when is-open?
       (mf/portal
        (mf/html
         [:div {:class (stl/css :dropdown-portal)
                :data-testid "theme-select-dropdown"
                :style {:top (:top rect)
                        :left (:left rect)
                        :width (:width rect)}}

          [:& dropdown {:show is-open?
                        :on-close on-close-dropdown}
           [:> theme-options* {:tokens-lib tokens-lib
                               :tokens-status tokens-status
                               :tokens-source tokens-source
                               :can-edit-tokens can-edit-tokens
                               :team-id team-id
                               :on-close on-close-dropdown}]]])
        container))]))
