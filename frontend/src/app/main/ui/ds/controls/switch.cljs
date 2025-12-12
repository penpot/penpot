;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.switch
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:switch
  [:map
   [:id {:optional true} :string]
   [:class {:optional true} :string]
   [:label {:optional true} [:maybe :string]]
   [:aria-label {:optional true} [:maybe :string]]
   [:default-checked {:optional true} [:maybe :boolean]]
   [:on-change {:optional true} [:maybe fn?]]
   [:disabled {:optional true} :boolean]])

(mf/defc switch*
  {::mf/schema schema:switch}
  [{:keys [id class label aria-label default-checked on-change disabled] :rest props} ref]
  (let [checked*   (mf/use-state default-checked)
        checked?   (deref checked*)

        disabled?  (d/nilv disabled false)

        has-label? (not (str/blank? label))

        handle-toggle
        (mf/use-fn
         (mf/deps on-change checked? disabled?)
         #(when-not disabled?
            (let [updated-checked? (not checked?)]
              (reset! checked* updated-checked?)
              (when on-change
                (on-change updated-checked?)))))

        handle-keydown
        (mf/use-fn
         (mf/deps handle-toggle)
         (fn [event]
           (dom/prevent-default event)
           (when-not disabled?
             (when (or (kbd/space? event) (kbd/enter? event))
               (handle-toggle event)))))

        props
        (mf/spread-props props {:ref ref
                                :role "switch"
                                :aria-label (when-not has-label?
                                              aria-label)
                                :class [class (stl/css-case :switch true
                                                            :off (false? checked?)
                                                            :neutral (nil? checked?)
                                                            :on (true? checked?))]
                                :aria-checked checked?
                                :tab-index (if disabled? -1 0)
                                :on-click handle-toggle
                                :on-key-down handle-keydown
                                :disabled disabled?})]

    [:> :div props
     [:div {:id id
            :class (stl/css :switch-track)}
      [:div {:class (stl/css :switch-thumb)}]]
     (when has-label?
       [:label {:for id
                :class (stl/css :switch-label)}
        label])]))
