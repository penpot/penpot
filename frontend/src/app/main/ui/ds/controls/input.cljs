;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.input
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.controls.utilities.hint-message :refer [hint-message*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:input
  [:map
   [:id {:optional true} :string]
   [:class {:optional true} :string]
   [:label {:optional true} :string]
   [:is-optional {:optional true} :boolean]
   [:type {:optional true} :string]
   [:max-length {:optional true} :int]
   [:variant {:optional true} [:maybe [:enum "seamless" "dense" "comfortable"]]]
   [:hint-message {:optional true} [:maybe :string]]
   [:hint-type {:optional true} [:maybe [:enum "hint" "error" "warning"]]]])

(mf/defc input*
  {::mf/forward-ref true
   ::mf/schema schema:input}
  [{:keys [id class label is-optional type max-length variant hint-message hint-type] :rest props} ref]
  (let [id (or id (mf/use-id))
        variant (d/nilv variant "dense")
        is-optional (d/nilv is-optional false)
        type (d/nilv type "text")
        max-length (d/nilv max-length max-input-length)
        has-hint (and (some? hint-message) (not (str/blank? hint-message)))
        has-label (not (str/blank? label))
        ref (or ref (mf/use-ref))
        props (mf/spread-props props {:ref ref
                                      :type type
                                      :id id
                                      :max-length max-length
                                      :has-hint has-hint
                                      :hint-type hint-type
                                      :variant variant})]
    [:div {:class (dm/str class " " (stl/css-case :input-wrapper true
                                                  :variant-dense (= variant "dense")
                                                  :variant-comfortable (= variant "comfortable")
                                                  :has-hint has-hint))}
     (when has-label
       [:> label* {:for id :is-optional is-optional} label])
     [:> input-field* props]
     (when has-hint
       [:> hint-message* {:id id
                          :message hint-message
                          :type hint-type}])]))

