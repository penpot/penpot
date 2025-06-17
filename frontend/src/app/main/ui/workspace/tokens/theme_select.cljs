;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.theme-select
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc themes-list
  [{:keys [themes active-theme-paths on-close grouped?]}]
  (when (seq themes)
    [:ul {:class (stl/css :theme-options)}
     (for [[_ {:keys [group name] :as theme}] themes
           :let [theme-id (ctob/theme-path theme)
                 selected? (get active-theme-paths theme-id)
                 select-theme (fn [e]
                                (dom/stop-propagation e)
                                (st/emit! (dwtl/toggle-token-theme-active? group name))
                                (on-close))]]
       [:li {:key theme-id
             :role "option"
             :aria-selected selected?
             :class (stl/css-case
                     :checked-element true
                     :sub-item grouped?
                     :is-selected selected?)
             :on-click select-theme}
        [:> text* {:as "span" :typography "body-small" :class (stl/css :label) :title name} name]
        [:> icon* {:icon-id i/tick
                   :aria-hidden true
                   :class (stl/css-case :check-icon true
                                        :check-icon-visible selected?)}]])]))

(defn- open-tokens-theme-modal
  []
  (modal/show! :tokens/themes {}))

(mf/defc theme-options
  [{:keys [active-theme-paths themes on-close]}]
  [:ul {:class (stl/css :theme-options :custom-select-dropdown)
        :role "listbox"}
   (for [[group themes] themes]
     [:li {:key group
           :aria-labelledby (dm/str group "-label")
           :role "group"}
      (when (seq group)
        [:> text* {:as "span" :typography "headline-small" :class (stl/css :group) :id (dm/str (str/kebab group) "-label") :title group} group])
      [:& themes-list {:themes themes
                       :active-theme-paths active-theme-paths
                       :on-close on-close
                       :grouped? true}]])
   [:li {:class (stl/css :separator)
         :aria-hidden true}]
   [:li {:class (stl/css-case :checked-element true
                              :checked-element-button true)
         :role "option"
         :on-click open-tokens-theme-modal}
    [:> text* {:as "span" :typography "body-small"} (tr "workspace.tokens.edit-themes")]
    [:> icon* {:icon-id i/arrow-right :aria-hidden true}]]])

(mf/defc theme-select
  [{:keys []}]
  (let [;; Store
        active-theme-paths (mf/deref refs/workspace-active-theme-paths-no-hidden)
        active-themes-count (count active-theme-paths)
        themes (mf/deref refs/workspace-token-theme-tree-no-hidden)
        can-edit?  (:can-edit (deref refs/permissions))
        ;; Data
        current-label (cond
                        (> active-themes-count 1) (tr "workspace.tokens.active-themes" active-themes-count)
                        (= active-themes-count 1) (some->> (first active-theme-paths)
                                                           (ctob/split-theme-path)
                                                           (remove empty?)
                                                           (str/join " / "))
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
         (mf/deps can-edit?)
         (fn [event]
           (when can-edit?
             (when-let [node (dom/get-current-target event)]
               (let [rect (dom/get-bounding-rect node)]
                 (swap! state* assoc
                        :is-open? true
                        :rect rect))))))]

    [:div {:on-click on-open-dropdown
           :disabled (not can-edit?)
           :aria-expanded is-open?
           :aria-haspopup "listbox"
           :tab-index "0"
           :role "combobox"
           :data-testid "theme-select"
           :class (stl/css-case :custom-select true
                                :disabled-select (not can-edit?))}
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
           [:& theme-options {:active-theme-paths active-theme-paths
                              :themes themes
                              :on-close on-close-dropdown}]]])
        (dom/get-body)))]))
