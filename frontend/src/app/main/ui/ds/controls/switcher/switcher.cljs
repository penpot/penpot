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
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:switcher
  [:and
   [:map
    [:id {:optional true} :string]
    [:label {:optional true} [:maybe :string]]
    [:aria-label {:optional true} [:maybe :string]]
    [:default-checked {:optional true} :boolean]
    [:on-change {:optional true} [:maybe fn?]]
    [:disabled {:optional true} :boolean]
    [:size {:optional true} [:enum "sm" "md" "lg"]]
    [:class {:optional true} :string]]
   [:fn {:error/message "Invalid Props"}
    (fn [props]
      (or (contains? props :label)
          (contains? props :aria-label)))]])

(mf/defc switcher*
  {::mf/schema schema:switcher}
  [{:keys [id label default-checked on-change disabled size aria-label class] :rest props}]
  (let [id (or id (mf/use-id))
        size (d/nilv size " md ")
        disabled (d/nilv disabled false)
        is-checked* (mf/use-state (d/nilv default-checked false))
        is-checked (deref is-checked*)

        ;; Toggle handler
        handle-toggle
        (mf/use-fn
         (mf/deps on-change is-checked* disabled)
         (fn []
           (when-not disabled
             (let [new-checked (not is-checked)]
               (reset! is-checked* new-checked)
               (when on-change
                 (on-change new-checked))))))

        ;; Keyboard events
        handle-keydown
        (mf/use-fn
         (mf/deps handle-toggle)
         (fn [event]
           (when (or (kbd/space? event) (kbd/enter? event))
             (dom/prevent-default event)
             (handle-toggle event))))

        has-label (not (str/blank? label))

        props (mf/spread-props props {:id id
                                      :role "switch"
                                      :aria-label (when-not has-label
                                                    aria-label)
                                      :class [class (stl/css :switcher)]
                                      :aria-checked is-checked
                                      :disabled disabled
                                      :on-click handle-toggle
                                      :on-key-down handle-keydown
                                      :tab-index (if disabled -1 0)})]

    [:> :div props
     (when has-label
       [:label {:for id
                :class (stl/css-case :switcher-label true
                                     :switcher-label-disabled disabled)}
        label])
     [:div {:class (stl/css-case :switcher-track true
                                 :switcher-checked is-checked
                                 :switcher-sm (= size "sm")
                                 :switcher-md (= size "md")
                                 :switcher-lg (= size "lg")
                                 :switcher-track-disabled-checked (and is-checked disabled))}
      [:div {:class (stl/css-case :switcher-thumb true
                                  :switcher-thumb-disabled disabled)}]]]))
