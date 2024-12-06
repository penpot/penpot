;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.components.controls.input-tokens
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.controls.input :refer [input*]]
   [rumext.v2 :as mf]))

(def ^:private schema::input-tokens
  [:map
   [:id :string]
   [:label :string]
   [:placeholder {:optional true} :string]
   [:default-value {:optional true} [:maybe :string]]
   [:class {:optional true} :string]
   [:error {:optional true} :boolean]
   [:value {:optional true} :string]])

(mf/defc input-tokens*
  {::mf/props :obj
   ::mf/forward-ref true
   ::mf/schema schema::input-tokens}
  [{:keys [class label id error value children] :rest props} ref]
  (let [ref   (or ref (mf/use-ref))
        props (mf/spread-props props {:id id
                                      :type "text"
                                      :class (stl/css :input)
                                      :aria-invalid error
                                      :value value
                                      :ref ref})]
    [:div {:class (dm/str class " " (stl/css-case :wrapper true
                                                  :input-error error))}
     [:label {:for id :class (stl/css :label)} label]
     [:div {:class (stl/css :input-wrapper)}
      (when (some? children)
        [:div {:class (stl/css :input-swatch)} children])
      [:> input* props]]]))
