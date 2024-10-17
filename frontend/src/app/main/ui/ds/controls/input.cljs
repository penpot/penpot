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
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private schema:input
  [:map
   [:class {:optional true} :string]
   [:icon {:optional true}
    [:and :string [:fn #(contains? icon-list %)]]]
   [:type {:optional true} :string]
   [:ref {:optional true} some?]])

(mf/defc input*
  {::mf/props :obj
   ::mf/schema schema:input}
  [{:keys [icon class type ref] :rest props}]
  (let [ref (or ref (mf/use-ref))
        type (or type "text")
        icon-class (stl/css-case :input true
                                 :input-with-icon (some? icon))
        props (mf/spread-props props {:class icon-class :ref ref :type type})
        handle-icon-click (mf/use-fn (mf/deps ref)
                                     (fn [_]
                                       (let [input-node (mf/ref-val ref)]
                                         (dom/select-node input-node)
                                         (dom/focus! input-node))))]
    [:> "span" {:class (dm/str class " " (stl/css :container))}
     (when icon [:> icon* {:id icon :class (stl/css :icon) :on-click handle-icon-click}])
     [:> "input" props]]))