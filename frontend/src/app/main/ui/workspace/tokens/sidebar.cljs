;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.common :refer [labeled-input]]
   [app.main.ui.workspace.tokens.context-menu :refer [token-context-menu]]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.main.ui.workspace.tokens.sets :refer [sets-list]]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.token-types :as wtty]
   [app.util.dom :as dom]
   [app.util.storage :refer [storage]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]
   [shadow.resource]))

(def lens:token-type-open-status
  (l/derived (l/in [:workspace-tokens :open-status]) st/state))

(def ^:private download-icon
  (i/icon-xref :download (stl/css :download-icon)))

(def selected-set-id
  (l/derived :selected-set-id st/state))

 ;; Event Functions -------------------------------------------------------------

(defn on-set-add-click [_event]
  (when-let [set-name (js/window.prompt "Set name")]
    (st/emit! (wdt/create-token-set {:name set-name}))))

;; Components ------------------------------------------------------------------

(mf/defc token-pill
  {::mf/wrap-props false}
  [{:keys [on-click token theme-token highlighted? on-context-menu] :as props}]
  (let [{:keys [name value resolved-value errors]} token
        errors? (and (seq errors) (seq (:errors theme-token)))]
    [:button {:class (stl/css-case :token-pill true
                                   :token-pill-highlighted highlighted?
                                   :token-pill-invalid errors?)
              :title (cond
                       errors? (sd/humanize-errors token)
                       :else (->> [(str "Token: " name)
                                   (str "Original value: " value)
                                   (str "Resolved value: " resolved-value)]
                                  (str/join "\n")))
              :on-click on-click
              :on-context-menu on-context-menu
              :disabled errors?}
     name]))

(mf/defc token-section-icon
  {::mf/wrap-props false}
  [{:keys [type]}]
  (case type
    :border-radius i/corner-radius
    :numeric [:span {:class (stl/css :section-text-icon)} "123"]
    :boolean i/boolean-difference
    :opacity [:span {:class (stl/css :section-text-icon)} "%"]
    :rotation i/rotation
    :spacing i/padding-extended
    :string i/text-mixed
    :stroke-width i/stroke-size
    :typography i/text
    ;; TODO: Add diagonal icon here when it's available
    :dimensions [:div {:style {:rotate "45deg"}} i/constraint-horizontal]
    :sizing [:div {:style {:rotate "45deg"}} i/constraint-horizontal]
    i/add))

(mf/defc token-component
  [{:keys [type tokens selected-shapes token-type-props active-theme-tokens]}]
  (let [open? (mf/deref (-> (l/key type)
                            (l/derived lens:token-type-open-status)))
        {:keys [modal attributes all-attributes title]} token-type-props

        on-context-menu (mf/use-fn
                         (fn [event token]
                           (dom/prevent-default event)
                           (dom/stop-propagation event)
                           (st/emit! (dt/show-token-context-menu {:type :token
                                                                  :position (dom/get-client-position event)
                                                                  :token-id (:id token)}))))

        on-toggle-open-click (mf/use-fn
                              (mf/deps open? tokens)
                              #(st/emit! (dt/set-token-type-section-open type (not open?))))
        on-popover-open-click (mf/use-fn
                               (fn [event]
                                 (let [{:keys [key fields]} modal]
                                   (dom/stop-propagation event)
                                   (modal/show! key {:x (.-clientX ^js event)
                                                     :y (.-clientY ^js event)
                                                     :position :right
                                                     :fields fields
                                                     :token-type type}))))

        on-token-pill-click (mf/use-fn
                             (mf/deps selected-shapes token-type-props)
                             (fn [event token]
                               (dom/stop-propagation event)
                               (when (seq selected-shapes)
                                 (st/emit!
                                  (wtch/toggle-token {:token token
                                                      :shapes selected-shapes
                                                      :token-type-props token-type-props})))))
        tokens-count (count tokens)]
    [:div {:on-click on-toggle-open-click}
     [:& cmm/asset-section {:icon (mf/fnc icon-wrapper [_]
                                    [:div {:class (stl/css :section-icon)}
                                     [:& token-section-icon {:type type}]])

                            :title title
                            :assets-count tokens-count
                            :open? open?}
      [:& cmm/asset-section-block {:role :title-button}
       [:button {:class (stl/css :action-button)
                 :on-click on-popover-open-click}
        i/add]]
      (when open?
        [:& cmm/asset-section-block {:role :content}
         [:div {:class (stl/css :token-pills-wrapper)}
          (for [token (sort-by :modified-at tokens)]
            (let [theme-token (get active-theme-tokens (wtt/token-identifier token))]
              [:& token-pill
               {:key (:id token)
                :token token
                :theme-token theme-token
                :highlighted? (wtt/shapes-token-applied? token selected-shapes (or all-attributes attributes))
                :on-click #(on-token-pill-click % token)
                :on-context-menu #(on-context-menu % token)}]))]])]]))

(defn sorted-token-groups
  "Separate token-types into groups of `:empty` or `:filled` depending if tokens exist for that type.
  Sort each group alphabetically (by their `:token-key`)."
  [tokens]
  (let [tokens-by-type (wtc/group-tokens-by-type tokens)
        {:keys [empty filled]} (->> wtty/token-types
                                    (map (fn [[token-key token-type-props]]
                                           {:token-key token-key
                                            :token-type-props token-type-props
                                            :tokens (get tokens-by-type token-key [])}))
                                    (group-by (fn [{:keys [tokens]}]
                                                (if (empty? tokens) :empty :filled))))]
    {:empty (sort-by :token-key empty)
     :filled (sort-by :token-key filled)}))

(mf/defc tokene-theme-create
  [_props]
  (let [group (mf/use-state "")
        name (mf/use-state "")]
    [:div {:style {:display "flex"
                   :flex-direction "column"
                   :gap "10px"}}
     [:& labeled-input {:label "Group name"
                        :input-props {:value @group
                                      :on-change #(reset! group (dom/event->value %))}}]
     [:& labeled-input {:label "Theme name"
                        :input-props {:value @name
                                      :on-change #(reset! name (dom/event->value %))}}]
     [:button {:on-click #(st/emit! (wdt/create-token-theme {:group @group
                                                             :name @name}))}
      "Create"]]))

(mf/defc themes-sidebar
  [_props]
  (let [open? (mf/use-state true)
        active-theme-ids (mf/deref refs/workspace-active-theme-ids)
        themes (mf/deref refs/workspace-ordered-token-themes)]
    [:div {:class (stl/css :sets-sidebar)}
     [:div {:class (stl/css :sidebar-header)}
      [:& title-bar {:collapsable true
                     :collapsed (not @open?)
                     :all-clickable true
                     :title "THEMES"
                     :on-collapsed #(swap! open? not)}]]
     (when @open?
       [:div
        [:style
         (str "@scope {"
              (str/join "\n"
                        ["ul { list-style-type: circle; margin-left: 20px; }"
                         ".spaced { display: flex; gap: 10px; justify-content: space-between;  }"
                         ".spaced-y { display: flex; flex-direction: column; gap: 10px }"
                         ".selected { font-weight: 600; }"
                         "b { font-weight: 600; }"])
              "}")]
        [:div.spaced-y
         {:style {:padding "10px"}}
         [:& tokene-theme-create]
         [:div.spaced-y
          [:b "Themes"]
          [:ul
           (for [[group themes] themes]
             [:li
              {:key (str "token-theme-group" group)}
              group
              [:ul
               (for [{:keys [id name] :as _theme} themes]
                 [:li {:key (str "tokene-theme-" id)}
                  [:div.spaced
                   name
                   [:div.spaced
                    [:button
                     {:on-click (fn [e]
                                  (dom/prevent-default e)
                                  (dom/stop-propagation e)
                                  (st/emit! (wdt/toggle-token-theme id)))}
                     (if (get active-theme-ids id) "✅" "❎")]
                    [:button {:on-click (fn [e]
                                          (dom/prevent-default e)
                                          (dom/stop-propagation e)
                                          (st/emit! (wdt/delete-token-theme id)))}
                     "🗑️"]]]])]])]]]])]))

(mf/defc sets-sidebar
  []
  (let [open? (mf/use-state true)]
    [:div {:class (stl/css :sets-sidebar)}
     [:div {:class (stl/css :sidebar-header)}
      [:& title-bar {:collapsable true
                     :collapsed (not @open?)
                     :all-clickable true
                     :title "SETS"
                     :on-collapsed #(swap! open? not)}]
      [:button {:class (stl/css :add-set)
                :on-click on-set-add-click}
       i/add]]
     (when @open?
       [:& sets-list])]))

(mf/defc tokens-explorer
  [_props]
  (let [open? (mf/use-state true)
        objects (mf/deref refs/workspace-page-objects)

        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)


        active-theme-tokens (sd/use-active-theme-sets-tokens)

        tokens (sd/use-resolved-workspace-tokens)
        token-groups (mf/with-memo [tokens]
                       (sorted-token-groups tokens))]
    [:article
     [:& token-context-menu]
     [:& title-bar {:collapsable true
                    :collapsed (not @open?)
                    :all-clickable true
                    :title "TOKENS"
                    :on-collapsed #(swap! open? not)}]
     (when @open?
       [:div.assets-bar
        (for [{:keys [token-key token-type-props tokens]} (concat (:filled token-groups)
                                                                  (:empty token-groups))]
          [:& token-component {:key token-key
                               :type token-key
                               :selected-shapes selected-shapes
                               :active-theme-tokens active-theme-tokens
                               :tokens tokens
                               :token-type-props token-type-props}])])]))

(defn dev-or-preview-url? [url]
  (let [host (-> url js/URL. .-host)
        localhost? (= "localhost" (first (str/split host #":")))
        pr? (str/ends-with? host "penpot.alpha.tokens.studio")]
    (or localhost? pr?)))

(defn location-url-dev-or-preview-url!? []
  (dev-or-preview-url? js/window.location.href))

(defn temp-use-themes-flag []
  (let [show? (mf/use-state (or
                             (location-url-dev-or-preview-url!?)
                             (get @storage ::show-token-themes-sets?)
                             false))]
    (mf/use-effect
     (fn []
       (letfn [(toggle! []
                 (swap! storage update ::show-token-themes-sets? not)
                 (reset! show? (get @storage ::show-token-themes-sets?)))]
         (set! js/window.toggleThemes toggle!))))
    show?))

(mf/defc tokens-sidebar-tab
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [_props]
  (let [show-sets-section? (deref (temp-use-themes-flag))]
    [:div {:class (stl/css :sidebar-tab-wrapper)}
     (when show-sets-section?
       [:div {:class (stl/css :sets-section-wrapper)}
        [:& themes-sidebar]
        [:& sets-sidebar]])
     [:div {:class (stl/css :tokens-section-wrapper)}
      [:& tokens-explorer]]
     [:button {:class (stl/css :download-json-button)
               :on-click wtc/download-tokens-as-json}
      download-icon
      "Export JSON"]]))
