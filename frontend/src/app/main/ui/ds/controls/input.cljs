;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.input
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private schema:input
  [:map
   [:class {:optional true} :string]
   [:icon {:optional true}
    [:and :string [:fn #(contains? icon-list %)]]]
   [:type {:optional true} :string]
   [:variant {:optional true} :string]])

(mf/defc input*
  {::mf/props :obj
   ::mf/forward-ref true
   ::mf/schema schema:input}
  [{:keys [icon class type variant] :rest props} ref]
  (let [ref   (or ref (mf/use-ref))
        type  (d/nilv type "text")
        props (mf/spread-props props
                               {:class (stl/css-case
                                        :input true
                                        :input-with-icon (some? icon))
                                :ref ref
                                :type type})

        on-icon-click
        (mf/use-fn
         (mf/deps ref)
         (fn [_event]
           (let [input-node (mf/ref-val ref)]
             (dom/select-node input-node)
             (dom/focus! input-node))))]

    [:> :span {:class (dm/str class " " (stl/css-case :container true
                                                      :input-seamless (= variant "seamless")))}
     (when (some? icon)
       [:> icon* {:icon-id icon :class (stl/css :icon) :on-click on-icon-click}])
     [:> :input props]]))
