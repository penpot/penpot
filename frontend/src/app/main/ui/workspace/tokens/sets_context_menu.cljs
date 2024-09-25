(ns app.main.ui.workspace.tokens.sets-context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.util.dom :as dom]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def sets-menu-ref
  (l/derived :token-set-context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  {::mf/props :obj}
  [{:keys [title value on-click]}]
  [:li
   {:class (stl/css :context-menu-item)
    :data-value value
    :on-click on-click}
   [:span {:class (stl/css :title)} title]])

(mf/defc menu
  [{:keys [token-set-id token-set-name]}]
  (let [{:keys [on-edit]} (sets-context/use-context)]
    [:ul {:class (stl/css :context-list)}
     [:& menu-entry {:title "Rename" :on-click #(on-edit token-set-id)}]
     [:& menu-entry {:title "Delete" :on-click #(st/emit! (wdt/delete-token-set token-set-name))}]]))

(mf/defc sets-context-menu
  []
  (let [mdata (mf/deref sets-menu-ref)
        top (+ (get-in mdata [:position :y]) 5)
        left (+ (get-in mdata [:position :x]) 5)
        width (mf/use-state 0)
        dropdown-ref (mf/use-ref)
        token-set-id (:token-set-id mdata)
        token-set-name (:token-set-name mdata)]
    (mf/use-effect
     (mf/deps mdata)
     (fn []
       (when-let [node (mf/ref-val dropdown-ref)]
         (reset! width (.-offsetWidth node)))))
    [:& dropdown {:show (boolean mdata)
                  :on-close #(st/emit! wdt/hide-token-set-context-menu)}
     [:div {:class (stl/css :token-set-context-menu)
            :ref dropdown-ref
            :style {:top top :left left}
            :on-context-menu prevent-default}
      [:& menu {:token-set-id token-set-id
                :token-set-name token-set-name}]]]))
