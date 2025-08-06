;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.utilities.input-field
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private schema:input-field
  [:map
   [:class {:optional true} :string]
   [:id :string]
   [:icon {:optional true}
    [:maybe [:and :string [:fn #(contains? icon-list %)]]]]
   [:has-hint {:optional true} :boolean]
   [:hint-type {:optional true} [:maybe [:enum "hint" "error" "warning"]]]
   [:type {:optional true} :string]
   [:max-length {:optional true} :int]
   [:variant {:optional true} [:enum "seamless" "dense" "comfortable"]]
   [:slot-start {:optional true} [:maybe some?]]
   [:slot-end {:optional true} [:maybe some?]]])

(mf/defc input-field*
  {::mf/forward-ref true
   ::mf/schema schema:input-field}
  [{:keys [id icon class type
           has-hint hint-type
           max-length variant
           slot-start slot-end] :rest props} ref]
  (let [input-ref (mf/use-ref)
        type  (d/nilv type "text")
        variant (d/nilv variant "dense")
        props (mf/spread-props props
                               {:class (stl/css-case
                                        :input true
                                        :input-with-icon (some? icon))
                                :ref (or ref input-ref)
                                :aria-invalid (when (and has-hint
                                                         (= hint-type "error"))
                                                "true")
                                :aria-describedby (when has-hint
                                                    (str id "-hint"))
                                :type (d/nilv type "text")
                                :id id
                                :max-length (d/nilv max-length max-input-length)})

        on-icon-click
        (mf/use-fn
         (mf/deps ref)
         (fn [_event]
           (let [input-node (mf/ref-val ref)]
             (dom/select-node input-node)
             (dom/focus! input-node))))]

    [:div {:class (dm/str class " " (stl/css-case :input-wrapper true
                                                  :has-hint has-hint
                                                  :hint-type-hint (= hint-type "hint")
                                                  :hint-type-warning (= hint-type "warning")
                                                  :hint-type-error (= hint-type "error")
                                                  :variant-seamless (= variant "seamless")
                                                  :variant-dense (= variant "dense")
                                                  :variant-comfortable (= variant "comfortable")))}
     (when (some? slot-start)
       slot-start)
     (when (some? icon)
       [:> icon* {:icon-id icon :class (stl/css :icon) :on-click on-icon-click}])
     [:> "input" props]
     (when (some? slot-end)
       slot-end)]))
