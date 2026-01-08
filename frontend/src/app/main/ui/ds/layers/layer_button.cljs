;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.layers.layer-button
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.refs :as refs]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private schema:layer-button
  [:map
   [:label :string]
   [:description {:optional true} [:maybe :string]]
   [:class {:optional true} :string]
   [:expandable {:optional true} :boolean]
   [:expanded {:optional true} :boolean]
   [:icon {:optional true} :string]
   [:on-toggle-expand {:optional true} fn?]
   [:on-context-menu {:optional true} fn?]])

(mf/defc layer-button*
  {::mf/schema schema:layer-button}
  [{:keys [label description class is-expandable expanded icon on-toggle-expand on-context-menu children] :rest props}]
  (let [button-props (mf/spread-props props
                                      {:class [class (stl/css-case :layer-button true
                                                                   :layer-button--expandable is-expandable
                                                                   :layer-button--expanded expanded)]
                                       :type "button"
                                       :on-click on-toggle-expand
                                       :on-context-menu on-context-menu})]
    [:div {:class (stl/css :layer-button-wrapper)}
     [:> "button" button-props
      [:div {:class (stl/css :layer-button-content)}
       (when is-expandable
         (if expanded
           [:> icon* {:icon-id i/arrow-down :class (stl/css :folder-node-icon)}]
           [:> icon* {:icon-id i/arrow-right :class (stl/css :folder-node-icon)}]))
       (when icon
         [:> icon* {:icon-id icon :class (stl/css :layer-button-icon)}])
       [:span {:class (stl/css :layer-button-name)}
        label]
       (when description
         [:span {:class (stl/css :layer-button-description)}
          description])
       [:span {:class (stl/css :layer-button-quantity)}]]]
     [:div {:class (stl/css :layer-button-actions)}
      children]]))
