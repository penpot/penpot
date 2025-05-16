;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.components.controls.input-tokens-value
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.workspace.tokens.components.controls.input-token-color-bullet :refer [input-token-color-bullet*]]
   [rumext.v2 :as mf]))

(def ^:private schema::input-tokens-value
  [:map
   [:label :string]
   [:placeholder {:optional true} :string]
   [:value {:optional true} [:maybe :string]]
   [:class {:optional true} :string]
   [:color {:optional true} [:maybe :string]]
   [:display-colorpicker {:optional true} fn?]
   [:error {:optional true} :boolean]])


(mf/defc input-tokens-value*
  {::mf/props :obj
   ::mf/forward-ref true
   ::mf/schema schema::input-tokens-value}
  [{:keys [class label placeholder error value color display-colorpicker] :rest props} ref]
  (let [id (mf/use-id)
        input-ref (mf/use-ref)
        swatch
        (when color
          (mf/html [:> input-token-color-bullet*
                    {:color color
                     :class (stl/css :slot-start)
                     :on-click display-colorpicker}]))

        props (mf/spread-props props {:id id
                                      :type "text"
                                      :class (stl/css :input)
                                      :placeholder placeholder
                                      :value value
                                      :variant "comfortable"
                                      :hint-type (when error "error")
                                      :slot-start swatch
                                      :ref (or ref input-ref)})]
    [:div {:class (dm/str class " " (stl/css-case :wrapper true
                                                  :input-error error))}
     [:> label* {:for id} label]
     [:> input-field* props]]))
