;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.controls.radio-cards
  "A choice of one among a few options, each a row as wide as its
  container with an icon, a name and one line that says what picking it
  does. For the choices `radio-buttons*` has no room to explain."
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(def ^:private schema:radio-card
  [:map
   [:id :string]
   [:value :string]
   [:label :string]
   [:description {:optional true} [:maybe :string]]
   [:icon {:optional true}
    [:maybe [:and :string [:fn #(contains? icon-list %)]]]]
   [:disabled {:optional true} [:maybe :boolean]]])

(def ^:private schema:radio-cards
  [:map
   [:class {:optional true} [:maybe :string]]
   [:name :string]
   [:selected {:optional true} [:maybe :string]]
   [:options [:vector {:min 1} schema:radio-card]]
   [:on-change {:optional true} [:maybe fn?]]])

(mf/defc radio-cards*
  "Each option is a native radio inside its label, so the arrow keys move
  the choice, a disabled option cannot be picked, and the accessible name
  of an option is its name and its description. `on-change` receives the
  value picked and the event. The `:rest` props (an `aria-label` or
  `aria-labelledby` naming the group) go to the group."
  {::mf/schema schema:radio-cards}
  [{:keys [class name selected options on-change] :rest props}]
  (let [options (if (array? options)
                  (mfu/bean options)
                  options)

        on-input-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (when (fn? on-change)
             (on-change (dom/get-target-val event) event))))

        props
        (mf/spread-props props {:class [class (stl/css :radio-cards)]
                                :role "radiogroup"})]

    [:> :div props
     (for [{:keys [id value label description icon disabled]} options]
       (let [checked  (= value selected)
             disabled (d/nilv disabled false)]
         [:label {:key id
                  :for id
                  :class (stl/css-case :card true
                                       :is-checked checked
                                       :is-disabled disabled)}
          [:input {:id id
                   :type "radio"
                   :class (stl/css :input)
                   :name name
                   :value value
                   :checked checked
                   :disabled disabled
                   :on-change on-input-change}]
          (when (some? icon)
            [:> icon* {:icon-id icon
                       :class (stl/css :icon)
                       :aria-hidden true}])
          [:span {:class (stl/css :text)}
           [:> text* {:as "span"
                      :typography t/body-medium
                      :class (stl/css :label)}
            label]
           (when (some? description)
             [:> text* {:as "span"
                        :typography t/body-small
                        :class (stl/css :description)}
              description])]]))]))
