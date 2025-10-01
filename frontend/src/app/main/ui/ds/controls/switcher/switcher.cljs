;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.switcher.switcher
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
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
   [:class {:optional true} :string]])

(mf/defc switcher*
  {::mf/forward-ref true
   ::mf/schema schema:switcher}
  [{:keys [id label checked default-checked on-change disabled size aria-label class] :rest props} ref]
  (let [id (or id (mf/use-id))
        size (d/nilv size "md")
        disabled (d/nilv disabled false)

        ;; TODO: review which one is better
        ;; Internal state for uncontrolled mode
        internal-checked* (mf/use-state (d/nilv default-checked false))
        internal-checked (deref internal-checked*)

        ;; Determine if controlled or uncontrolled
        controlled? (some? checked)
        current-checked (if controlled? checked internal-checked)

        ;; Toggle handler
        handle-toggle
        (mf/use-fn
         (mf/deps controlled? current-checked on-change internal-checked* disabled)
         (fn [event]
           (when-not disabled
             (let [new-checked (not current-checked)]
               (when-not controlled?
                 (reset! internal-checked* new-checked))
               (when on-change
                 (on-change new-checked event))))))

        ;; Keyboard events
        handle-keydown
        (mf/use-fn
         (mf/deps handle-toggle)
         (fn [event]
           (when (or (kbd/space? event) (kbd/enter? event))
             (dom/prevent-default event)
             (handle-toggle event))))

        ;; Label click handler
        handle-label-click
        (mf/use-fn
         (mf/deps handle-toggle)
         (fn [event]
           (dom/prevent-default event)
           (handle-toggle event)))

        has-label (not (str/blank? label))
        effective-aria-label (if has-label
                               (or aria-label label)
                               (tr "ds.switcher.aria-label"))

        props (mf/spread-props props {:id id
                                      :ref ref
                                      :role "switch"
                                      :tabIndex (if disabled -1 0)
                                      :aria-checked current-checked
                                      :aria-disabled disabled
                                      :aria-label effective-aria-label
                                      :class (stl/css-case :switcher true
                                                           :switcher-checked current-checked
                                                           :switcher-disabled disabled
                                                           :switcher-sm (= size "sm")
                                                           :switcher-md (= size "md")
                                                           :switcher-lg (= size "lg"))
                                      :on-click handle-toggle
                                      :on-key-down handle-keydown})]

    [:div {:class [class (stl/css :switcher-wrapper)]}
     (when has-label
       [:label {:for id
                :class (stl/css-case :switcher-label true
                                     :switcher-label-disabled disabled)
                :on-click handle-label-click}
        label])
     [:> :div props
      [:div {:class (stl/css-case :switcher-track true
                                  :switcher-track-disabled disabled)}
       [:div {:class (stl/css-case :switcher-thumb true
                                   :switcher-thumb-disabled disabled)}]]]]))
