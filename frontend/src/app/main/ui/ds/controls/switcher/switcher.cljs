;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.switcher.switcher
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:switcher
  [:map
   [:id {:optional true} :string]
   [:label {:optional true} :string]
   [:checked {:optional true} :boolean]
   [:default-checked {:optional true} :boolean]
   [:on-change {:optional true} [:maybe fn?]]
   [:disabled {:optional true} :boolean]
   [:size {:optional true} [:enum "sm" "md" "lg"]]
   [:aria-label {:optional true} [:maybe :string]]
   [:class {:optional true} :string]
   [:data-testid {:optional true} :string]])

(mf/defc switcher*
  {::mf/forward-ref true
   ::mf/schema schema:switcher}
  [{:keys [id label checked default-checked on-change disabled size aria-label class data-testid] :rest props} ref]
  (let [id (or id (mf/use-id))
        size (keyword (d/nilv size "md"))
        disabled (d/nilv disabled false)
        
        ;; Internal state for uncontrolled mode
        internal-checked* (mf/use-state (d/nilv default-checked false))
        internal-checked (deref internal-checked*)
        
        ;; Determine if controlled or uncontrolled
        controlled? (some? checked)
        current-checked (if controlled? checked internal-checked)
        
        ;; Handle toggle
        handle-toggle (mf/use-fn
                       (mf/deps controlled? current-checked on-change internal-checked*)
                       (fn [event]
                         (when-not disabled
                           (let [new-checked (not current-checked)]
                             (when-not controlled?
                               (reset! internal-checked* new-checked))
                             (when on-change
                               (on-change new-checked event))))))
        
        ;; Handle keyboard events
        handle-keydown (mf/use-fn
                        (mf/deps handle-toggle)
                        (fn [event]
                          (when (or (= (.-key event) " ") (= (.-key event) "Enter"))
                            (.preventDefault event)
                            (handle-toggle event))))
        
        ;; Label click handler
        handle-label-click (mf/use-fn
                            (mf/deps handle-toggle)
                            (fn [event]
                              (.preventDefault event)
                              (handle-toggle event)))
        
        has-label (not (str/blank? label))
        effective-aria-label (or aria-label (when-not has-label "Toggle switch"))]
    
    [:div {:class (dm/str class " " (stl/css-case :switcher-wrapper true))}
     (when has-label
       [:label {:for id
                :class (stl/css :switcher-label)
                :on-click handle-label-click}
        label])
     [:div {:id id
            :ref ref
            :role "switch"
            :tabIndex (if disabled -1 0)
            :aria-checked current-checked
            :aria-disabled disabled
            :aria-label effective-aria-label
            :data-testid data-testid
            :class (stl/css-case :switcher true
                                 :is-checked current-checked
                                 :is-disabled disabled
                                 :switcher--sm (= size :sm)
                                 :switcher--md (= size :md)
                                 :switcher--lg (= size :lg))
            :on-click handle-toggle
            :on-key-down handle-keydown}
      [:div {:class (stl/css :switcher__track)}
       [:div {:class (stl/css :switcher__thumb)}]]]]))

;; Export as default
(def switcher switcher*)
